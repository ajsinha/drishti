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
# Pack developer guide

A **domain pack** teaches Drishti an industry: its kinds of entity, the commands that open them, how they link, how
their screens look, a vocabulary, sample data, roles, the connectors its data comes through, a guide. It is configuration
and content: no Java and no Python the server runs. This guide is for the person who **writes** a pack. It builds one end
to end, a help-desk pack, with real commands and the real output they gave, then covers each topic in depth, using the
shipped packs as the reference.

Who reads what:

| You are | Read |
|---|---|
| a user or administrator: which packs ship, turning them on and off, who sees which, the demo lakes | [Part V. Domain packs: the packs that ship, turning them on, who sees which](#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which) |
| writing a pack | this guide |
| writing the Sutras of a pack | [Sutra guide](SUTRA_DEVELOPER_GUIDE.md), every key in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md), the workbench in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench) |
| connecting a pack to a database, a lake or a queue | [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) and the per-connector pages they list |
| turning JSON files or a schema into a pack without writing files by hand | [tutorial 5](../tutorials/05-build-a-pack.md), [DATA_GUIDE.md](DATA_GUIDE.md#5-schema-to-pack-generate-a-pack-from-json-schema), [Generating a pack from JSON Lines](#generating-a-pack-from-json-lines) |

Contents:

1. [What a pack is made of](#what-a-pack-is-made-of)
2. [The worked example: a help-desk pack](#the-worked-example-a-help-desk-pack)
3. [Build it, step by step](#build-it-step-by-step) (steps 1 to 17)
4. [`pack.yaml` key by key](#packyaml-key-by-key)

5. [Part II. Modelling in depth: kinds, data and content](#part-ii-modelling-in-depth-kinds-data-and-content): the lake layout, several source systems for one kind, derived kinds, samples, deep documents,
   vocabulary, About text and glossary, guides and help, facts for Home, roles and field masks, fragments and docstrings, Calc snippets
6. [Part III. Around the pack: inheritance, tests, versions, the registry](#part-iii-around-the-pack-inheritance-tests-versions-the-registry): the signed registry, loading while the server runs, inheritance, tests, versioning,
   pack fragments from the workbench, verifying a pack
7. [Part IV. Generated packs, the README standard, JSON Lines](#part-iv-generated-packs-the-readme-standard-json-lines): how the shipped packs are generated, the README standard, a pack from JSON Lines
8. [Part V. Domain packs: the packs that ship, turning them on, who sees which](#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which): for users and administrators: what a pack is, the packs that ship, the demo lakes, turning packs on and off (Admin → Packs), who sees which, loading while the server runs, pack codes, upgrading and removing
9. [Part VI. The funds and ETFs pack](#part-vi-the-funds-and-etfs-pack): the 200 largest US mutual funds and ETFs from SEC data: FUND, the look-through, HELD and TRACK, the contracts of fund, fund-holder and index-funds, sources and licences, the refresh script
10. [Part VII. Groupings and analytics, computed](#part-vii-groupings-and-analytics-computed): LEIG, LEIC, SIC, ECG, CORR, the USEQ ratios and the PXH analytics computed instead of stored: each command, its contract, reconciliation, the derived connector, settings
11. [Part VIII. Changing Drishti's code: the developer guide](#part-viii-changing-drishtis-code-the-developer-guide): for developers of Drishti itself: the repository, building and running from source, project rules, how a request travels, recipes, testing, debugging, pitfalls, releasing

Parts II to IV were three companion guides (PACK_MODELLING_GUIDE.md, PACK_LIFECYCLE_GUIDE.md, PACK_GENERATORS_GUIDE.md) until 2.9.6;
their sections keep their names, so their old anchors are the same here.

## What a pack is made of

A pack is one folder under `config/packs/`. The folder name is the pack's name. Here is the finished help-desk pack of this
guide, `docs/guides/examples/pack/helpdesk/`:

```text
helpdesk/
├── pack.yaml                    the manifest: kinds, commands, links, columns, roles, alerts, connectors, console extras
├── sutras/
│   └── ticket.v1.sutra.yaml     a layout (a Sutra) for tickets
├── tests/
│   └── ticket/                  sample tickets and expect.yaml: what `sutra test` checks about the Sutra
├── config/
│   ├── semantics.yaml           field-name hints and labels for inference
│   ├── formats.yaml             a number format of the pack ("30 h")
│   ├── about.yaml               what a ticket is and what its fields mean (About this page)
│   ├── workspaces.yaml          a starter workspace
│   └── help.yaml                the pack's card in the help centre
├── guides/
│   └── helpdesk.md              the guide that card opens
├── python/
│   └── queue-by-status.py       a Calc snippet
└── samples/
    ├── catalog.jsonl             the list of sample entities (kind, id, title, subtitle)
    ├── ticket.jsonl             the sample tickets, one JSON document a line
    ├── agent.jsonl
    └── client.jsonl
```

And here is how the largest shipped pack, `config/packs/trading/`, uses the same parts:

```text
trading/
├── pack.yaml                    5 kinds, 5 mnemonics, 5 connectors (three Delta tables as a union, a Kafka stream, a derived kind)
├── sutras/                      134 Sutras and 6 shared blocks, in folders for people: common/ endur/ calypso/ murex/ pages/
├── tests/                       134 folders: two or more sample trades per Sutra, and expect.yaml
├── config/                      about.yaml, formats.yaml, help.yaml
├── guides/                      trading.md, trading-products.md
├── python/                      9 Calc snippets
└── samples/                     catalog.jsonl and trade.jsonl (765 trades of the three source systems)
```

**Attribute names are UPPER_SNAKE.** Every shipped pack names every attribute the way production systems write them (`TRADE_ID`,
`COUNTERPARTY_ID`, `NET_MTM`, `ZERO_RATE`), and so does the help-desk pack (`TICKET_ID`, `AGE_HOURS`). Nothing in Drishti requires
it (a document's fields are whatever your source holds), but one convention across packs keeps link fields, searches, BI datasets
and About text consistent, and it is what the generators and `pack new` write.

Words used throughout:

| Word | Meaning | Example |
|---|---|---|
| **kind** | a type of entity; a kind belongs to exactly one pack | `ticket`, `agent`, `trade` |
| **mnemonic** | the short command that opens a kind | `TKT` opens a `ticket` |
| **id** | one entity's identifier | `TKT-1001` |
| **Sutra** | a layout for a kind, one YAML file starting `rachana: 1` | `sutras/ticket.v1.sutra.yaml` |
| **block** | panels several Sutras share, one YAML file starting `rachana: 1` with `block:` | `sutras/common/legs.v1.block.yaml` |
| **connector** | a configured source of data; a site resource (one file in `config/connectors/`) that the pack names, and may suggest a template for | `agent-totals` (a derived one) |
| **route** | which connector answers a kind | `agent-load: agent-totals` |
| **inference** | the layout Drishti works out from a document when no Sutra matches | the agent view below |

A pack with only `pack.yaml` and `samples/` already works: inference lays out every kind. Everything else makes it
better, and each piece is optional.

## The worked example: a help-desk pack

Four kinds: **ticket** (`TKT`), **agent** (`AGT`), **client** (`CLI`) and **agent-load** (`LOAD`), a *derived* kind:
nothing stores it, the server sums each agent's open tickets. The pack has links with badges, impact (F8), pick-list
columns and a Pivot tab, two roles, an alert suggestion, a vocabulary, About text, a Sutra with tests, a guide, a starter
workspace and monitor, a Calc snippet, a file connector for real data and a signed registry entry. It is small enough to
read in one sitting and uses every part of a pack except the lake layout (the shipped packs show that:
[Large kinds](#large-kinds-the-lake-layout)).

The finished pack is in the repository. Open it beside this guide:

| Part | File |
|---|---|
| manifest | [pack.yaml](examples/pack/helpdesk/pack.yaml) |
| Sutra and its tests | [ticket.v1.sutra.yaml](examples/pack/helpdesk/sutras/ticket.v1.sutra.yaml), [tests/ticket/](examples/pack/helpdesk/tests/ticket/expect.yaml) |
| vocabulary | [semantics.yaml](examples/pack/helpdesk/config/semantics.yaml), [formats.yaml](examples/pack/helpdesk/config/formats.yaml) |
| About this page | [about.yaml](examples/pack/helpdesk/config/about.yaml) |
| samples | [catalog.jsonl](examples/pack/helpdesk/samples/catalog.jsonl) and `samples/ticket.jsonl`, `agent.jsonl`, `client.jsonl` |
| guide and starters | [helpdesk.md](examples/pack/helpdesk/guides/helpdesk.md), [help.yaml](examples/pack/helpdesk/config/help.yaml), [workspaces.yaml](examples/pack/helpdesk/config/workspaces.yaml) |

It is not in `config/packs/`, so no server loads it by accident. To follow along, build it yourself in `config/packs/helpdesk/`
(steps 1 to 15), or copy the finished folder there.

**A scratch server to try things on.** Every step ends with something you can see. Use a scratch server and console on
ports of their own, so that nothing of yours is touched: the seven shipped packs plus the one you are writing
(`DRISHTI_PACKS` is explained in [Part V. Domain packs: the packs that ship, turning them on, who sees which](#turning-packs-on); the start-up recipe, the token and the run configurations are in
[GETTING_STARTED.md](GETTING_STARTED.md)). Keep the demo source on (`DRISHTI_DEMO_ENABLED=true`, the default): it serves the pack's
`samples/`. Security is off unless you turn it on, so the `curl` lines below need no token; on a server with security on add
`-H "Authorization: Bearer $DRISHTI_TOKEN"`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
DRISHTI_PORT=18996 DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,helpdesk \
  java -jar drishti-server/target/drishti-server-2.9.6-exec.jar
DRISHTI_BACKEND_URL=http://127.0.0.1:18996 DRISHTI_CONSOLE_PORT=17996 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

The commands below use `B=http://localhost:18996/api/v1` for the server's API. **Packs follow the folder while the server runs:**
a change to `pack.yaml`, `config/` or `samples/` is picked up within a few seconds (the packs folder is watched, `drishti.packs.poll`),
checked with the loaded packs and swapped in; a bad edit keeps the last good version running and shows on Admin → Packs. A pack folder
copied into `config/packs/` of a running server is loaded too, without naming it in `DRISHTI_PACKS` (it is remembered in
`<data>/packs/added.yaml`). No change needs a restart (see [Versioning a pack](#versioning-a-pack) for what a change needs, and
[Part V. Domain packs: the packs that ship, turning them on, who sees which](#live-reload-the-packs-folder-is-watched)).

## Build it, step by step

This is the pack **by hand**, to show every file. If your data is JSON Lines or has a JSON Schema, you do not need to write these files:
`drishti.py pack new` ([below](#generating-a-pack-from-json-lines)) and [tutorial 5](../tutorials/05-build-a-pack.md) turn a schema or a folder
into a pack, deploy it and roll it back (the rules are in [DATA_GUIDE.md](DATA_GUIDE.md#5-schema-to-pack-generate-a-pack-from-json-schema)), and
[tutorial 6](../tutorials/06-design-a-screen.md) designs its screen.

Each step says what to type and what you should see. The outputs are real: they were produced by running the steps on the
finished pack on a 2.3.0 server. (Your ticking numbers may differ a little: the ticket's age walks up and down.)

### Step 1. Create the folders

```bash
mkdir -p config/packs/helpdesk/{samples,sutras,tests/ticket,guides,config,python}
```

### Step 2. The smallest working manifest

`config/packs/helpdesk/pack.yaml`. Every file in the repository carries the copyright header: copy the comment block from
the top of any shipped `pack.yaml`, or run `python3 tools/license_headers.py --fix` when you are done (the build's
`LicenseHeaderTest` fails otherwise).

```yaml
pack: helpdesk                    # must equal the folder name
version: 0.1.0                    # X.Y.Z
code: HELP                        # typed alone, opens the pack's overview
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
  examples:                       # shown on /t
    - ["TKT TKT-1001", "An open ticket · history, agent and client"]
    - ["AGT AGT-07", "An agent · inferred"]
```

Only `pack` is required. The other keys are explained in [`pack.yaml`, key by key](#packyaml-key-by-key); the finished
file has more keys than this one, which later steps add. Choose kinds and mnemonics no shipped pack uses (`trade`, `TRD`,
`counterparty` and `CPTY` are taken): two packs may not own one kind, and two unrelated packs may not define one mnemonic differently.

### Step 3. Sample data

The built-in `demo` source serves every loaded pack's `samples/` folder, so the pack works before there is a real data
store. `samples/catalog.jsonl` lists every sample (`kind` and `id` are required; `title` and `subtitle` are what the
type-ahead shows):

```json
[
  { "kind": "ticket", "id": "TKT-1001", "title": "TKT-1001", "subtitle": "Ticket · Cannot sign in · High" },
  { "kind": "ticket", "id": "TKT-1002", "title": "TKT-1002", "subtitle": "Ticket · Invoice missing · Normal" },
  { "kind": "ticket", "id": "TKT-1003", "title": "TKT-1003", "subtitle": "Ticket · Export fails · Low" },
  { "kind": "agent",  "id": "AGT-07",   "title": "AGT-07",   "subtitle": "Agent · Priya N. · Identity" },
  { "kind": "client", "id": "CLI-ACME",   "title": "CLI-ACME",   "subtitle": "Client · Acme Freight Ltd" },
  { "kind": "client", "id": "CLI-GLOBEX", "title": "CLI-GLOBEX", "subtitle": "Client · Globex Retail plc" }
]
```

and `samples/<kind>.jsonl` holds the documents of a kind, **one document a line**, in the catalogue's order, each with a `_meta`
block. The full format, and what each `_meta` key does, is in [Samples](#samples). `samples/ticket.jsonl` (the first line is the
ticket the guide uses throughout):

```json
{"TICKET_ID":"TKT-1001","SUBJECT":"Cannot sign in","STATUS":"Open","PRIORITY":"High","ASSIGNEE":"AGT-07","CLIENT":"CLI-ACME","CLIENT_NAME":"Acme Freight Ltd","AGE_HOURS":30,"HISTORY":[{"DATE":"2026-09-28","EVENT":"Opened by client"},{"DATE":"2026-09-29","EVENT":"Assigned to AGT-07"},{"DATE":"2026-09-30","EVENT":"Client replied"}],"_meta":{"source":"helpdesk","generation":1,"live":true,"walk":{"AGE_HOURS":1}}}
{"TICKET_ID":"TKT-1002","SUBJECT":"Invoice missing","STATUS":"Waiting on client","PRIORITY":"Normal","ASSIGNEE":"AGT-07","CLIENT":"CLI-GLOBEX","CLIENT_NAME":"Globex Retail plc","AGE_HOURS":6,"HISTORY":[{"DATE":"2026-09-30","EVENT":"Opened by client"}],"_meta":{"source":"helpdesk","generation":1,"live":false}}
{"TICKET_ID":"TKT-1003","SUBJECT":"Export fails on large files","STATUS":"Closed","PRIORITY":"Low","ASSIGNEE":"AGT-07","CLIENT":"CLI-ACME","CLIENT_NAME":"Acme Freight Ltd","AGE_HOURS":52,"HISTORY":[{"DATE":"2026-09-26","EVENT":"Opened by client"},{"DATE":"2026-09-28","EVENT":"Closed: fixed in 4.2"}],"_meta":{"source":"helpdesk","generation":1,"live":false}}
```

`"live": true` with `"walk": {"AGE_HOURS": 1}` makes the age tick while someone watches the ticket: a live view with no
code. `samples/agent.jsonl` holds one agent and `samples/client.jsonl` two clients, written the same way
(`{"AGENT_ID":"AGT-07","NAME":"Priya N.","TEAM":"Identity","OPEN_TICKETS":2,"SHIFT":"EMEA early",…}`,
`{"CLIENT_ID":"CLI-ACME","NAME":"Acme Freight Ltd","TIER":"Gold","COUNTRY":"GB",…}`). A catalogue entry with no document is skipped.

**Never rely on the demo connector to serve a pack's data.** The `demo` source is for building a pack on your own machine; a
cluster runs with it off (`DRISHTI_DEMO_ENABLED=false`), and every page whose data only `samples/` holds then fails with `DRS-1001
no source holds …`. A pack that ships data puts it on the standard pattern of [step 16](#step-16-real-data-a-connector-and-a-route)
(test data, `lake demo`, a Delta connector of the pack); `samples/` then keeps only a few reference documents for the README and
the guide.

### Step 4. Load it

Start the scratch server with `helpdesk` in `DRISHTI_PACKS` (above), or copy the folder into the packs folder of a running server.
Check that it loaded:

```bash
B=http://localhost:18996/api/v1
curl -s $B/packs | python3 -c 'import json,sys; [print(p["name"], p["version"]) for p in json.load(sys.stdin) if p["name"]=="helpdesk"]'
```

```text
helpdesk 0.1.0
```

If the server stopped instead (or, on a running server, Admin → Packs shows the pack *refused*), the message says why: a typo
in a folder name, a kind another pack owns, a clash with an unrelated pack ([TROUBLESHOOTING.md](TROUBLESHOOTING.md#building-and-starting)).
The console needs no restart: it asks the server which packs are on.

### Step 5. Open it (inference only)

In the terminal (`/t`) type `TK`: the dropdown offers `TKT` *Ticket*. Type `TKT TKT-1001` and press Enter. There is no
Sutra yet, so **inference** lays the ticket out. See what it did for the agent, which will never get a Sutra here:

```bash
curl -s $B/views/agent/AGT-07 | python3 -c '
import json,sys; v=json.load(sys.stdin)
print([(s["label"], s["text"]) for s in v["strip"]]); print([(p["id"], p["kind"]) for p in v["panels"]])'
```

```text
[('Agent ID', 'AGT-07'), ('Name', 'Priya N.'), ('Team', 'Identity'), ('Open tickets', '2'), ('Shift', 'EMEA early')]
[('built', 'provenance'), ('refs', 'links')]
```

*How this view was built* says `inference only`; inference turned `OPEN_TICKETS` into *Open tickets* and led with the id field.
`TKT TKT-100` is a **pick list** (`3 of 3 tickets match`), and a bare `TKT-1001` opens the ticket because of the `^TKT-` pattern.
See [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra) for how inference decides.

### Step 6. Links and badges

Add to `graph:` in `pack.yaml`:

```yaml
  fields:                         # a field whose value is another entity's id becomes a link
    ASSIGNEE: { kind: agent,  label: Assigned agent }
    CLIENT:   { kind: client, label: Client }
  badges:                         # Rachana-EL, evaluated on the TARGET of the link
    agent:  "$.OPEN_TICKETS + ' open'"
    client: "$.TIER"
```

Save, wait a few seconds for the reload (or press Retry now in Admin → Packs) and open `TKT TKT-1001`. *Linked entities* now lists
the agent and the client, with their badges. The view-model answer (`curl -s $B/views/ticket/TKT-1001`, the `links` panel) says it
exactly:

```text
{'label': 'Assigned agent', 'text': 'AGT-07', 'link': {'kind': 'agent', 'id': 'AGT-07', 'mnemonic': 'AGT'}, 'badge': '2 open', 'status': 'resolved'}
{'label': 'Client', 'text': 'CLI-ACME', 'link': {'kind': 'client', 'id': 'CLI-ACME', 'mnemonic': 'CLI'}, 'badge': 'Gold', 'status': 'resolved'}
```

A link field name means **one kind everywhere**: if another loaded pack also declares a `CLIENT` field for a
different kind, the pack is refused. The shipped packs claim `COUNTERPARTY`, `PORTFOLIO`, `BOOK`, `LEGAL_ENTITY`, `TRADER_ID`,
`TRADE_ID`, `TRADE_IDS`, `ECONOMY`, `NEWS_ID` and many more; pick names that fit your domain and are not taken.

A link field links from **every kind's documents** unless it says otherwise. When the name is generic and other packs' documents carry
it with other codes, add `scope: pack`: the field is then a link only in the documents of the declaring pack's own kinds, and plain
data everywhere else. World-economy does this for `JURISDICTION` (its values are `JUR-` ids), so a gleif entity's `JURISDICTION`
(`US-DE`) and a core legal entity's (`DE`) show as text instead of a link to nothing:

```yaml
  fields:
    JURISDICTION: { kind: jurisdiction, label: Country, scope: pack }   # pack: this pack's kinds only; all (the default): every kind
```

### Step 7. Impact (F8)

Add under `graph:`:

```yaml
  impact:
    follow: [CLIENT]                        # roll dependents up through these link fields
    measures: { ticket: "$.AGE_HOURS" }     # Rachana-EL per dependent kind, summed per group
    formats:  { ticket: amount0 }
```

Save, wait for the reload, open `AGT AGT-07` and press `F8`. The same answer from the API (`curl -s $B/impact/agent/AGT-07`, condensed):

```text
level 1  ticket  TKT-1001 (30)  TKT-1002 (6)  TKT-1003 (52)   total 88
level 2  client  CLI-ACME, CLI-GLOBEX                         via "CLIENT"
```

Tickets depend on the agent directly (they name her in `ASSIGNEE`), with their ages summed; the clients roll up
through the tickets' `CLIENT` field. That answers *which clients are affected if Priya is off sick?* Impact finds
dependents through the sources' reverse lookups; the demo source has them, as do the file, lake and database connectors.
See the [Impact guide](USER_GUIDE.md#f8--impact).

### Step 8. Pick-list columns and a Pivot tab

```yaml
columns:                          # key fields beside each id in pick lists and searches
  ticket: [SUBJECT, STATUS, PRIORITY, ASSIGNEE, AGE_HOURS]
  agent:  [NAME, TEAM, OPEN_TICKETS]
  client: [NAME, TIER, COUNTRY]

pivot:                            # a Pivot tab on the kind's search results
  ticket:
    fields: [STATUS, PRIORITY, ASSIGNEE, CLIENT, AGE_HOURS]
    rows: [ASSIGNEE]
    columns: [STATUS]
    values: [{ field: AGE_HOURS, agg: sum }]
```

`TKT TKT-100` now answers with exactly those five columns (`['$.SUBJECT', '$.STATUS', '$.PRIORITY', '$.ASSIGNEE', '$.AGE_HOURS']`,
three tickets, and a `pivot` block for the Pivot tab). The server's reply to `TKT STATUS=open` (one match, so the ticket opens at once
in the terminal) lists them with their labels, the field the command names first:

```bash
curl -s -G $B/search --data-urlencode 'q=TKT STATUS=open' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["columns"]); print(d["labels"])'
```

```text
['$.STATUS', '$.SUBJECT', '$.PRIORITY', '$.ASSIGNEE', '$.AGE_HOURS']
{'$.STATUS': 'Status', '$.SUBJECT': 'Subject', '$.PRIORITY': 'Priority', '$.ASSIGNEE': 'Agent', '$.AGE_HOURS': 'Age (h)'}
```

(*Agent* and *Age (h)* come from the vocabulary of step 10.) Details, and how the Pivot tab computes on the server:
[`columns`](#columns-the-key-fields-of-a-pick-list) and [`pivot`](#pivot-a-pivot-tab-on-search-results).

### Step 9. Roles, field masks and an alert suggestion

```yaml
roles:
  support:      { kinds: [ticket, agent, client, agent-load] }               # opens the pack's kinds, nothing else
  support-lead: { kinds: [ticket, agent, client, agent-load], raw: true, calc: true }   # sees every field unmasked, may use Calc

alerts:
  - kind: ticket
    name: "Open more than a day"
    when: "$.AGE_HOURS > 24 && $.STATUS != 'Closed'"
    severity: warn
    message: "${$.TICKET_ID}: open ${fmt($.AGE_HOURS, 'age0')} (${$.CLIENT_NAME})"
```

Roles take effect when sign-in is on; they appear in *Admin → Roles* marked **built-in**. The difference between
`support` and `support-lead` is `raw`: a role without it sees every field named in the site's `drishti.security.redact` as `•••`
(for example `CLIENT_NAME`, if the site treats client names as confidential). Masks are a site setting, not a pack key,
so a pack can say who is exempt but not what is hidden: see [Roles and field masks](#roles-and-field-masks). The alert
appears in the Alert dialog of every ticket: open `TKT TKT-1001`, click **Alert**, and pick *Open more than a day*.

### Step 10. Vocabulary

`config/formats.yaml` (a format of your own) and `config/semantics.yaml` (hints inference and labels use):

```yaml
# config/formats.yaml
formats:
  age0: { type: number, decimals: 0, suffix: " h" }
```

```yaml
# config/semantics.yaml
roles:                              # a field-name pattern (case ignored) → how inference shows such fields
  - { role: age, pattern: "HOURS$", fmt: age0, weight: 80 }
  - { role: label, pattern: "PRIORITY|TEAM|TIER|SHIFT", weight: 64 }
labels:                             # field → label, everywhere (strips, tables, pick-list headings)
  AGE_HOURS: Age (h)
  ASSIGNEE: Agent
  HOURS_OPEN: Hours open
  OLDEST_HOURS: Oldest (h)
idFields: ["TICKET_ID", "AGENT_ID", "CLIENT_ID"]
```

Save and wait for the reload. The age reads `30 h` and the pick-list column is headed *Age (h)*. The format file and the hints are
explained in [Vocabulary](#vocabulary-labels-formats-and-inference-hints).

### Step 10b. About this page: say what a ticket is

Every view has an *About this page* drawer (`?`). It already says where the data came from and why the page looks as it
does; the sentence at the top and the meaning of each field are yours to write, in one more file,
`config/about.yaml`:

```yaml
# config/about.yaml
about: 1
kinds:
  ticket:
    title: Support ticket
    about: >-
      ${$.TICKET_ID} ("${$.SUBJECT}") is a ${lower($.PRIORITY)}-priority ticket for ${$.CLIENT_NAME}, ${lower($.STATUS)} for
      ${$.AGE_HOURS} hours and assigned to ${$.ASSIGNEE}.
    glossary:
      AGE_HOURS:
        term: Age
        means: Hours since the ticket was opened. It keeps counting while the ticket is open.
        unit: hours
      PRIORITY:
        term: Priority
        means: How soon the team has promised to look at it.
        values:
          High: Within four working hours
          Normal: Within one working day
          Low: Within five working days
      # … SUBJECT, STATUS (with its values), ASSIGNEE, CLIENT, HISTORY.DATE and HISTORY.EVENT the same way
```

Save, wait for the reload and press `?` on `TKT TKT-1001`: the drawer's first section reads *TKT-1001 ("Cannot sign in") is a high-priority
ticket for Acme Freight Ltd, open for 30 hours and assigned to AGT-07.*, and the glossary explains each field the screen shows. The
same from the API (`curl -s $B/views/ticket/TKT-1001/explain`, condensed):

```text
"about": {"pack": {"name": "helpdesk", "title": "Help desk"}, "kindTitle": "Support ticket",
          "text": "TKT-1001 (\"Cannot sign in\") is a high-priority ticket for Acme Freight Ltd, open for 30 hours and assigned to AGT-07."}
"glossary": [{"key": "PRIORITY", "label": "Priority", "shownIn": ["strip"], "term": "Priority", "means": "How soon the team has promised to look at it.",
              "values": {"High": "Within four working hours"}, "origin": "helpdesk:glossary.PRIORITY"}, …]
```

The sentence is a template over the ticket, so it changes when the ticket does; a value's meaning is shown for the value on the page
(`High` only). The keys, the rules and the checks are in [About text and glossary](#about-text-and-glossary);
`drishti.py pack about-check config/packs/helpdesk` lists every field a Sutra shows that has no glossary entry (step 17).

### Step 11. A Sutra: the layout you want

Inference is a good start; a **Sutra** fixes the layout. `sutras/ticket.v1.sutra.yaml`: the copyright header as `#`
comments, then `rachana: 1`, the name, the version and the layout.

```yaml
rachana: 1
sutra: ticket
version: 1
description: A support ticket with its history, agent and client.
match: { kind: ticket, priority: 10 }
title: { pill: "Ticket", id: $.TICKET_ID, with: $.CLIENT_NAME }
strip:
  - { label: Subject, bind: $.SUBJECT }
  - { label: Status, bind: $.STATUS, tone: status }
  - { label: Priority, bind: $.PRIORITY }
  - { label: Age, bind: $.AGE_HOURS, fmt: age0, emphasis: true }
  - { label: Agent, bind: "link($.ASSIGNEE, 'agent')" }
  - { label: Client, bind: "link($.CLIENT, 'client')" }
panels:
  - id: history
    kind: ladder
    title: History
    code: HIST
    key: F2
    rows: $.HISTORY
    highlight: "#index == size($.HISTORY) - 1"
    columns:
      - { label: Date, bind: "@.DATE", fmt: date }
      - { label: Event, bind: "@.EVENT" }
  - { id: built, kind: provenance, title: How this view was built }
  - { id: refs, kind: links, title: Linked entities, code: REFS, area: right }
keys: { F7: "link($.ASSIGNEE, 'agent')", F9: raw }
```

Pack Sutras are watched: the server picks the file up within about a second, no restart. Check it:

```bash
curl -s $B/sutras/problems
```

```text
{}
```

Open `TKT TKT-1001`: the strip reads *Subject Cannot sign in · Status Open · Priority High · Age 30 h · Agent AGT-07 · Client CLI-ACME*.

![The ticket, laid out by the Sutra: a title with the client, six figures, a History ladder with the latest event lit, links with badges](img/packs/04-ticket-view.jpg)

*How this view was built* now says `Sutra ticket v1 + inference`. A mistake (`kind: ladderr`) is reported with its line
and the view keeps the last good version ([runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md)).

Writing Sutras is taught in the [Sutra guide](SUTRA_DEVELOPER_GUIDE.md); every key is in
[RACHANA_REFERENCE.md](RACHANA_REFERENCE.md). You can design the Sutra in the **Build workbench** instead of by hand:
start from the samples, let auto-design draft it, edit on the canvas, and export the result into your pack as a
[fragment](#pack-fragments-from-the-build-workbench) ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench)).

**Many Sutras for one kind.** Sutras may sit in any sub-folders of `sutras/`: the folder is for people only, read recursively, and
Drishti infers nothing from its name; each Sutra's `match:` alone decides what it lays out (a `where:` over the document and a
`priority`). The trading pack is the model to copy when one kind has many shapes: one Sutra per source system and product type
(`match: { kind: trade, where: "$.SOURCE_SYSTEM == 'MUREX' && $.PRODUCT_TYPE == 'IRS_FIXFLOAT'", priority: 10 }`), a product's
variants as panels with `when:` inside its one Sutra rather than as more Sutras, and the panels every product shares in **blocks**
(`sutras/common/legs.v1.block.yaml`, used as `{ use: legs@1, with: { rate-fmt: price4 } }`;
[Shared panel blocks](RACHANA_REFERENCE.md#shared-panel-blocks-use)). Admin → Packs → your pack → **Sutra coverage**
(`GET /api/v1/packs/<pack>/coverage?kind=<kind>`) shows which Sutra lays out each combination of the fields your Sutras match on,
as the data has it, and flags *none* and *several* ([Several source systems for one kind](#several-source-systems-for-one-kind-the-trading-pack)).

### Step 12. Put the Sutra under test

A Sutra that renders today can break with next month's data. `tests/<sutra>/` holds sample documents and an
`expect.yaml`; `sutra test` renders the Sutra against every sample and checks the expectations, with no server and no
port:

```text
tests/ticket/cases.jsonl          two cases, one a line: {"case": "TKT-1001", "document": {...}}
tests/ticket/expect.yaml
```

```yaml
noErrors: true            # no panel in an error state on any sample (the default)
nonEmpty: [history]       # these panels must render with data on every sample
```

```bash
java -jar drishti-server/target/drishti-server-2.9.6-exec.jar sutra test config/packs/helpdesk --junit target/sutra-tests.xml
```

```text
     ticket: help coverage 8/8 (100%)
ok   config/packs/helpdesk/sutras/ticket.v1.sutra.yaml (2 samples)
```

Exit code `0`. *Help coverage* is the share of the fields the Sutra shows that `config/about.yaml` explains (8 of 8 once the
glossary of step 10b is complete; 2 of 8 with only `AGE_HOURS` and `PRIORITY`). `target/sutra-tests.xml` is JUnit XML for your CI's
report step. Now break the expectation on purpose (`nonEmpty: [history, timeline]`):

```text
ticket / TKT-1001: panel timeline is not in the Sutra
ticket / TKT-1002: panel timeline is not in the Sutra
     ticket: help coverage 8/8 (100%)
FAIL config/packs/helpdesk/sutras/ticket.v1.sutra.yaml (2 samples)
```

Exit code `1`. A mistake in the Sutra itself fails `sutra lint`, with the line:

```text
PROBLEM config/packs/helpdesk/sutras/ticket.v1.sutra.yaml
config/packs/helpdesk/sutras/ticket.v1.sutra.yaml:30 DRS-2021 unknown panel kind 'ladderr'; expected one of kv, table, tabs, line, area, hbar, ladder, links, status, provenance, markdown, gauge, surface, waterfall, histogram, scatter, candlestick, graph, timeline, pivot, metric, map
```

The Maven build runs `sutra test` over every pack that has a `tests/` folder, so a Sutra change that breaks
a pack fails the build: see [Testing a pack](#testing-a-pack).

### Step 13. Guide, help card, starters

`guides/helpdesk.md` describes the domain (not the sample records) and has a **Finding things** section;
`config/help.yaml` puts it in the help centre and makes it the guide F1 opens on the terminal; `config/workspaces.yaml`
offers a starter workspace. Their contents are in [Guides and help](#guides-and-help) and in the files linked above.
Then in `pack.yaml`:

```yaml
console:
  examples:
    - ["TKT TKT-1001", "An open ticket · history, agent and client"]
    - ["AGT AGT-07", "An agent · inferred"]
    - ["LOAD AGT-07", "A derived kind · the agent's open tickets summed"]
  workspaces: config/workspaces.yaml
  help: config/help.yaml
  monitors:
    Queue watch: [ { kind: ticket, id: TKT-1001 }, { kind: ticket, id: TKT-1002 } ]
```

Within a minute the help centre has a card *The help-desk pack*, *Views → Workspaces* offers *Ticket desk*, and *Views →
Monitors* offers *Queue watch*.

![The pack's guide in the help centre](img/packs/03-pack-help-guide.jpg)

### Step 14. A derived kind

*How many tickets does each agent have open, and for how long?* No source holds that; the server computes it. Add to
`pack.yaml`:

```yaml
kinds: [ticket, agent, client, agent-load]
mnemonics:
  LOAD: { kind: agent-load, label: Agent workload (derived from tickets) }
connectors:
  agent-totals:
    plugin: derived
    kinds: [agent-load]
    settings:
      refresh: 30s
      agent-load:
        from: ticket
        group-by: $.ASSIGNEE            # one agent-load entity per assignee
        where: $.STATUS != 'Closed'
        id-field: AGENT
        members: TICKET_IDS
        fields:
          TICKET_COUNT: count
          HOURS_OPEN: sum $.AGE_HOURS
          OLDEST_HOURS: max $.AGE_HOURS
routes:
  agent-load: agent-totals
```

and `TICKET_IDS: { kind: ticket, label: Ticket }` under `graph.fields`, so the members show as links, and
`agent-load: [TICKET_COUNT, HOURS_OPEN, OLDEST_HOURS]` under `columns`. Save and wait for the reload: the server writes
`config/connectors/agent-totals.yaml` from the template (a connector is a site resource: [step 16](#step-16-real-data-a-connector-and-a-route))
and computes the kind. Type `LOAD AGT-07`:

```bash
curl -s $B/views/agent-load/AGT-07 | python3 -c 'import json,sys; print([(s["label"], s["text"]) for s in json.load(sys.stdin)["strip"]])'
```

```text
[('Agent', 'AGT-07'), ('Derived from', 'ticket'), ('Hours open', '36'), ('Oldest (h)', '30 h'), ('Ticket count', '2'), ('Member count', '2')]
```

![LOAD AGT-07: Hours open 36, Oldest 30 h, Ticket count 2, summed from the open tickets](img/packs/05-derived-load.jpg)

TKT-1003 is closed, so it is not a member: 30 + 6 = 36 hours over two tickets. The first computation runs within `refresh` of the
reload, so a `LOAD AGT-07` typed in the first seconds may still say *no source holds agent-load/AGT-07*. The connector is healthy
(`agent-totals ['agent-load'] UP`). All the options of a derived kind are in
[Derived kinds](#derived-kinds-entities-computed-from-other-kinds).

### Step 15. A Calc snippet

Calc is Python in the user's browser (`Alt+C` on a view). A pack switches it on for its kinds and may ship starter
code. In `pack.yaml`:

```yaml
python:
  enabled: true
```

and `python/queue-by-status.py` (three comment lines, then the code; the copyright header above them is not shown to
the user):

```python
# title: Hours open by status
# description: The ticket's own age and status as a one-row DataFrame, to start a queue analysis from.
# kinds: ticket

import pandas as pd
pd.DataFrame([{"status": view.doc["STATUS"], "hours": view.doc["AGE_HOURS"]}])
```

Only roles with `calc: true` see Calc (here `support-lead`). More in [Calc: Python snippets](#calc-python-snippets).

### Step 16. Real data: a connector and a route

**Data a pack ships: the standard pattern.** Every document a shipped pack serves follows one pattern, whatever its origin
(generated, or published public data such as the us-equities and gleif packs):

1. the documents as gzipped JSON Lines under `data/testdata/<pack>/<kind>/` (a snapshot, then a file of changes a business day; or
   one dated `snapshot-<date>.jsonl.gz` the same on every day), each dataset listed in `data/testdata/testdata.yaml` with its pack,
   kind, Delta connector and key (`tools/testdata/make_testdata.py` and `tools/testdata/publicsnap.py` write it; never by hand);
2. `python3 tools/drishti.py lake demo` (or `tools/build_demo_lakes.py`) builds them into the demo lake;
3. a Delta connector in the pack's `pack.yaml` serves them (`plugin: delta`, its `domain`, the kinds, `layout.<kind>.columns` for the
   fields searches and the search form filter on, `layout.<kind>.type-ahead` for the columns a name is found by).

`PacksServedWithoutDemoTest` (drishti-server) fails the build when a kind of a shipped pack has no such connector and data.

Samples are for demos. Real tickets live somewhere else; here, the ticketing system exports each business day's tickets as one
JSON Lines file, the layout the `file` plugin reads ([FILE_CONNECTOR.md](../connectors/FILE_CONNECTOR.md)):

```text
data/files/helpdesk/2026-10-16/ticket.jsonl      one ticket a line, the day's export
```

```json
{"TICKET_ID": "TKT-2001", "SUBJECT": "Password reset loop", "STATUS": "Open", "PRIORITY": "High", "ASSIGNEE": "AGT-07", "CLIENT": "CLI-GLOBEX", "CLIENT_NAME": "Globex Retail plc", "AGE_HOURS": 3, "HISTORY": [{"DATE": "2026-10-16", "EVENT": "Opened by client"}]}
```

Then name the connector, give it a template, and route tickets to it (a connector is a site resource; the pack suggests it, see
[Connectors: the pack names them, the site owns them](#connectors-the-pack-names-them-the-site-owns-them)):

```yaml
connectors:
  agent-totals: { … as in step 14 … }
  helpdesk-store:
    plugin: file
    kinds: [ticket]
    settings:
      root: ${HELPDESK_FILES_ROOT:${drishti.data.dir:./data}/files}   # <root>/<domain>/<yyyy-mm-dd>/<kind>.jsonl
      domain: helpdesk
      id-field: TICKET_ID           # a plain document per line: its id is this field
      rescan-seconds: 30
routes:
  agent-load: agent-totals
  ticket: helpdesk-store            # tickets are read from this connector first
```

Save. When the pack reloads (a few seconds) the server writes `config/connectors/helpdesk-store.yaml` from the template, starting with the
line `# Generated at first start from the connector template of pack 'helpdesk'. Edit it freely: an edited file is never overwritten; an unedited one follows the pack's template at an upgrade.` and a stamp line, and never
overwrites it once edited. Check the connector, then open `TKT TKT-2001`:

```bash
curl -s $B/sources | python3 -c 'import json,sys; [print(s["name"], s["kinds"], s["health"]) for s in json.load(sys.stdin)["sources"] if s["name"]=="helpdesk-store"]'
```

```text
helpdesk-store ['ticket'] UP
```

*How this view was built* names `helpdesk-store` as the source, for business date 2026-10-16; the strip reads *Subject Password reset
loop · Status Open · Priority High · Age 3 h · Agent AGT-07 · Client CLI-GLOBEX*. The route makes `helpdesk-store` answer tickets first;
a ticket it does not hold is looked for in the other sources, so the sample tickets still open from the demo source.
`TKT AGE_HOURS < 10` now finds a sample and the exported ticket together:

```text
2 [('TKT-1002', 6), ('TKT-2001', 3)]
```

The derived `LOAD` kind reads tickets through the normal routing, so it counts the exported ones too (at its next `refresh`).

Three things the run taught, worth knowing before you point a connector at real files:

- **The template is used once.** Changing `helpdesk-store` in `pack.yaml` afterwards (another `id-field`, fewer kinds) does not touch the
  file already written. Edit the file, or put it back to the pack's current template: `drishti.py connector reset helpdesk-store`
  (or *Reset to pack default* in Admin → Connectors) rewrites it and keeps the earlier text in `<data>/connectors/history/`.
- **A dated file is the whole day.** For a business date that has a `<date>/ticket.jsonl`, that file alone answers; an undated
  `<domain>/ticket.jsonl` serves only the dates that have no dated file.
- **A line without the id is skipped and counted**, and Health says so: `UP (1 unreadable line in 2026-10-16/ticket.jsonl (line 1: no id
  ("id" in a row, or the id-field "id" in a plain document)))` is what a missing `id-field` looks like.

For a database, a lake, Kafka or S3 only the `plugin` and `settings` change; see the connector pages. A site can point the connector
elsewhere without touching the pack: edit `root:` in `config/connectors/helpdesk-store.yaml` (or in Admin → Connectors), and the running
server restarts that one connector; `HELPDESK_FILES_ROOT=/srv/exports` still works because the template, and so the generated file, reads it.
The finished example pack in the repository leaves this step out, so that it needs no data folder.

### Step 17. Check it, and publish it

```bash
python3 tools/license_headers.py --fix                                  # a header on every new file
drishti-console/.venv/bin/python tools/drishti.py pack check config/packs/helpdesk --tail 3
drishti-console/.venv/bin/python tools/drishti.py pack about-check config/packs/helpdesk
```

```text
== helpdesk: sutra lint: exit 0
ok      config/packs/helpdesk/sutras/ticket.v1.sutra.yaml
== helpdesk: sutra test: exit 0
     ticket: help coverage 8/8 (100%)
ok   config/packs/helpdesk/sutras/ticket.v1.sutra.yaml (2 samples)

== helpdesk: 0 field(s) shown without glossary text in 0 Sutra(s); 0 placeholder TODO text(s) in config/about.yaml
```

(`pack check` prints the pack's absolute path; it is shortened here.) With only two glossary entries, `about-check` exits `1` and names
the gaps: `ticket.v1: SUBJECT, STATUS, ASSIGNEE, CLIENT, HISTORY.DATE, HISTORY.EVENT`. Then sign it and publish it to a registry:

```bash
drishti-console/.venv/bin/python tools/drishti.py pack keygen --out ~/drishti-keys/acme          # once per publisher
drishti-console/.venv/bin/python tools/drishti.py pack publish config/packs/helpdesk --registry /srv/drishti-registry \
    --key ~/drishti-keys/acme.pem --publisher acme
```

```text
private key: /home/you/drishti-keys/acme.pem (keep it secret; it signs packs)
public key:  MCowBQYDK2VwAyEAcYvYL5cL9D3XYx6w49WZ4oAnDrhjAfRT95Twju2ug+A=
servers trust it with:  drishti.packs.registry.trusted-keys.<publisher>: <public key>

published helpdesk 0.1.0 (10424 bytes, sha256 1661eb4570b9…) signed by acme
```

An administrator trusts the publisher's key and presses **Install** in *Admin → Packs*:

![Admin → Packs, From the registry: helpdesk 0.1.0 by acme, trusted, with an Install button](img/packs/01-admin-packs-registry.jpg)

![After Install: installed 0.1.0, loaded 0.1.0, with Roll back](img/packs/02-registry-installed.jpg)

The server checked the archive's SHA-256 and signature, unpacked it, and put it to use at once, without a restart; the pack is on
(`helpdesk 0.1.0` in the pack list):

![The helpdesk row of Admin → Packs after the install](img/packs/02b-helpdesk-pack-row.jpg)

The signing model and every refusal are in [the signed registry](#a-signed-pack-registry-publishing-and-installing).
Last, [verify the pack](#verifying-a-pack): Admin → Health, `sutras/problems`, the CLI, and the checklist at the end of
this guide.

### What you built, key by key

| Step | Key or file | What the user gets |
|---|---|---|
| 2 | `pack`, `version`, `code`, `title`, `description`, `kinds`, `mnemonics`, `graph.id-patterns`, `console.examples` | `TKT TKT-1001`, `TKT-1001` alone, suggestions, `HELP` for the pack's overview |
| 3 | `samples/catalog.jsonl`, `samples/<kind>.jsonl` | data with no database, a live ticking age |
| 6 | `graph.fields`, `graph.badges` | links between tickets, agents and clients, with badges |
| 7 | `graph.impact` | F8 on an agent: tickets, then clients |
| 8 | `columns`, `pivot` | useful pick lists and a Pivot tab |
| 9 | `roles`, `alerts` | access control, `raw`, Calc rights; one-click alert rules |
| 10 | `config/formats.yaml`, `config/semantics.yaml` | `30 h`, *Age (h)* |
| 10b | `config/about.yaml` | a sentence about the ticket and the meaning of its fields in the *About this page* drawer |
| 11 | `sutras/` | the layout you designed |
| 12 | `tests/` | the Sutra under CI |
| 13 | `guides/`, `console.help`, `console.workspaces`, `console.monitors` | help card, F1, starters |
| 14 | `connectors` (`derived`), `routes` | an entity computed from others |
| 15 | `python` | Calc and a starter snippet |
| 16 | `connectors` (`file`), `routes` | real data |
| 17 | `pack check`, `pack about-check`, the registry | CI checks; install, upgrade, roll back |

## `pack.yaml`, key by key

Below, every key at a glance, then `config/packs/trading/pack.yaml` annotated (abridged), then the keys for inheritance and real
data from `config/packs/counterparty-risk/pack.yaml`. Every key here is read either by the server's pack loader
(`drishti-packs/…/PackLoader.java`) or by the console (`drishti-console/core/packs.py`); keys not listed are ignored.

**Every key at a glance.** Only `pack` is required; a pack with nothing else loads and does nothing.

| Key | Read by | One-line example | What it does | Details |
|---|---|---|---|---|
| `pack` | server | `pack: helpdesk` | The pack's name; must equal the folder name | below |
| `sample` | server | `sample: true` | Marks the pack as a demonstration: an administrator can hide sample packs from business users (`drishti.packs.samples`) | [Sample packs](#sample-packs) |
| `loads` | server | `loads: { … }` | Which kinds the ETL is expected to land each business day, by when, and who is told | [DATA_GUIDE.md](DATA_GUIDE.md#6-configuration-reference) |
| `code` | server | `code: HELP` | The pack's short code: typed alone on the command line it opens the pack's overview (`/p/<pack>`). It must not equal a mnemonic (a mnemonic wins) | [Pack codes](#pack-codes) |
| `version` | server | `version: 1.2.0` | **X.Y.Z**; shown in About, Admin → Health and Admin → Packs, checked by deploys and the registry | [Versioning](#versioning-a-pack) |
| `title`, `description` | both | `title: Help desk` | The pack's name and paragraph in menus, help and admin pages | below |
| `extends` (or `requires`) | server | `extends: [market-data, trading]` | Inherit everything from these packs | [Inheritance](#inheritance) |
| `kinds` | server | `kinds: [ticket, agent]` | The kinds this pack owns | below |
| `mnemonics` | server | `TKT: { kind: ticket, label: Ticket }` | The commands | below |
| `graph.id-patterns` | server | `- { pattern: "^TKT-", kind: ticket }` | Recognise a bare id's kind | below |
| `graph.fields` | server | `ASSIGNEE: { kind: agent, label: Assigned agent }` | Turn a field's value into a link (`scope: pack`: in this pack's kinds only) | below |
| `graph.badges` | server | `agent: "$.OPEN_TICKETS + ' open'"` | Text shown beside a link, read from the target | below |
| `graph.impact` | server | `follow: [CLIENT]`, `measures: { ticket: "$.AGE_HOURS" }` | What F8 rolls up and sums | below |
| `columns` | server | `ticket: [SUBJECT, STATUS, PRIORITY]` | Key fields in pick lists and searches | [columns](#columns-the-key-fields-of-a-pick-list) |
| `pivot` | server | `trade: { rows: [PRODUCT_TYPE], columns: [SOURCE_SYSTEM] }` | A Pivot tab on the kind's search results and pick lists | [pivot](#pivot-a-pivot-tab-on-search-results) |
| `form` | server | `trade: [ { path: QUANTITY, type: amount } ]` | The search form of each kind (`FORM <pack>`): its fields, their types and operators | [form](#form-the-search-form-of-a-kind) |
| `history` | server | `equity: { chart: [PRICE] }` | How a kind looks over time (`EQ EQ-CSCA 3M`): its columns, parts, chart fields and limits; never needed, any dated kind has a history | [history](#history-a-kind-over-time) |
| `roles` | server | `support: { kinds: [ticket, agent] }` | Roles this pack adds | [Roles](#roles-and-field-masks) |
| `python` | server | `python: { enabled: true }` | Calc (Python in the browser, `Alt+C`) on this pack's kinds, and starter snippets (also `python/*.py`) | [Calc](#calc-python-snippets) |
| `connectors` | server | `[helpdesk-store]` (names), or `helpdesk-store: { plugin: file, … }` (a mapping: the pack's templates) | The connectors the pack reads through. A list names site connectors; a mapping names them **and** suggests each one's definition | [below](#connectors-the-pack-names-them-the-site-owns-them) |
| `connector-templates` | server | `helpdesk-store: { plugin: file, … }` | Suggested definitions to go with a list of names; written to `config/connectors/<name>.yaml` at the first start if no such file exists | [below](#connectors-the-pack-names-them-the-site-owns-them) |
| `routes` | server | `ticket: helpdesk-store` | Which connector answers a kind | [below](#connectors-the-pack-names-them-the-site-owns-them) |
| `ingest` | tooling | `trade: { key: TRADE_ID, date: BUSINESS_DATE, flatten: { NOTIONAL: LEGS[0].NOTIONAL } }` | Which field is each kind's id and which its business date, for `drishti.py data ingest --pack`, `lake build --pack` (`--key` is then optional) and `pack check`; `flatten`: values inside lists that `lake build` writes as plain fields and columns ([Large kinds](#large-kinds-the-lake-layout)). **Optional and ignored by the server** (a pack loads the same without it) | [below](#generating-a-pack-from-json-lines) |
| `alerts` | server | `- { kind: ticket, name: …, when: "$.AGE_HOURS > 24" }` | Suggested alert rules | below |
| `sutras`, `formats`, `semantics`, `samples`, `about` | server | `sutras: sutras` | Where the pack's folders and files are | below |
| `console.examples` | console | `- ["TKT TKT-1001", "An open ticket"]` | Example commands on `/t` and the landing page | below |
| `console.workspaces` | console | `workspaces: config/workspaces.yaml` | Starter workspaces | below |
| `console.help` | console | `help: config/help.yaml` | Help-centre cards and F1 targets | [Guides and help](#guides-and-help) |
| `console.monitors` | console | `Queue watch: [ { kind: ticket, id: TKT-1001 } ]` | Starter monitors | below |

Values may use `${VARIABLE:default}`, resolved from the environment when the server starts
(`root: ${HELPDESK_FILES_ROOT:${drishti.data.dir:./data}/files}`).

The trading pack's manifest, annotated and abridged (the file is generated by `tools/packgen/banking/make_packs.py`; its
connectors and derived kinds are in [Several source systems for one kind](#several-source-systems-for-one-kind-the-trading-pack)):

```yaml
pack: trading                   # REQUIRED. Must equal the folder name, or the pack is refused.
version: 1.0.0                  # X.Y.Z. Shown on About, Admin → Health and Admin → Packs.
code: TRDS                      # typed alone: the pack's overview
title: Trading                  # display name (pack switcher, help, About). Default: the pack name.
description: >-                 # one paragraph for About and the help centre
  Trades from every booking system (Endur, Calypso, Murex) in one production schema …
extends: [core, market-data]    # inherit their kinds, links, roles, connectors … (Inheritance, below)

kinds: [trade, counterparty-trades, portfolio-trades, legal-entity-trades, index-trades]   # the kinds this pack OWNS. A kind belongs to
                                                                                          # exactly one installed pack.
sutras: sutras                  # where the Sutras are (the default); read recursively
formats: config/formats.yaml    # the pack's named formats (the default)

mnemonics:                      # the commands. MNEMONIC: { kind, label }
  TRD:  { kind: trade, label: Trade }                     # "TRD MX-20000001 <GO>" opens kind trade, id MX-20000001
  CTRD: { kind: counterparty-trades, label: Counterparty trades (derived from trades) }
  PTRD: { kind: portfolio-trades, label: Portfolio trades (derived from trades) }
  LTRD: { kind: legal-entity-trades, label: Legal entity trades (derived from trades) }
  ITRD: { kind: index-trades, label: Index trades (derived from trades) }

graph:                          # how entities link to each other
  id-patterns:                  # a bare id typed on its own is recognised by a regex (Endur's numeric ids are not: type TRD 1000001)
    - { pattern: "^(MX|CLY)-\\d+$", kind: trade }
  fields:                       # a document field whose value is an id of another kind becomes a link
    TRADE_ID:  { kind: trade, label: Trade }
    TRADE_IDS: { kind: trade, label: Trade }     # a list of ids: each one a link (a netting set's members)
  impact:                       # F8: what is summed for each dependent kind
    measures: { trade: "first($.LEGS).NOTIONAL" }
    formats:  { trade: compact }

connectors: { trade-endur: …, trade-calypso: …, trade-murex: …, trading-stream: …, trade-pages: … }   # the names and their templates
routes:
  trade: trade-endur            # the union is read whole whichever of its connectors the route names
  counterparty-trades: trade-pages
  # … one line per derived kind

columns:                        # pick-list and search columns per kind
  trade: [SOURCE_SYSTEM, PRODUCT_TYPE, TRADE_STATUS, TRADE_DATE, BUY_SELL_CODE, CURRENCY, QUANTITY, MATURITY_DATE, PORTFOLIO, COUNTERPARTY]
pivot:                          # a Pivot tab on trade searches (below)
  trade: { fields: [SOURCE_SYSTEM, PORTFOLIO, CURRENCY, …], rows: [PRODUCT_TYPE], columns: [SOURCE_SYSTEM],
           values: [{ field: QUANTITY, agg: sum }], filters: [PRODUCT_GROUP, TRADE_STATUS] }
form:                           # FORM TRDS: the search form (below)
  trade: [ { path: TRADE_ID, label: Trade id, type: id, group: Identity, order: 1 }, … ]

console:                        # read by the console only
  examples:                     # [command, description] pairs on the terminal home and landing page
    - ["TRD MX-20000001", "Interest rate swap (Murex) · legs side by side, flows and fixings"]
    - ["TRD 1000001", "Commodity swap (Endur) · legs, resets, events"]
    - ["TRD CLY-3000086", "Single-name CDS (Calypso) · premium leg payments"]
    - ["CTRD CP-ALDERSHOT", "A counterparty's trades in every system · payments, notional by product"]
  help: config/help.yaml        # help centre cards ("guides:") and F1 targets ("contextual:")
python: { enabled: true }       # Calc on trades; the snippets are python/*.py
roles:                          # roles this pack adds. ROLE: { kinds, raw?, author?, approve?, admin?, calc? }
  trader: { kinds: [trade, ir-curve, repo-curve, fx-spot, …, counterparty, book, …] }
```

The help-desk pack adds the keys trading does not use: `alerts:` (step 9) and `console.workspaces` / `console.monitors` (step 13):

```yaml
alerts:                         # suggested alert rules, offered in the Alert dialog for that kind
  - kind: ticket
    name: "Open more than a day"             # what the user sees in the list of suggestions
    when: "$.AGE_HOURS > 24 && $.STATUS != 'Closed'"   # Rachana-EL condition, checked on every change
    severity: warn                           # info, warn or critical
    message: "${$.TICKET_ID}: open ${fmt($.AGE_HOURS, 'age0')} (${$.CLIENT_NAME})"   # ${…} is evaluated
console:
  workspaces: config/workspaces.yaml         # starter workspaces (a "templates:" map), offered on /w
  monitors:                                  # starter monitors: NAME: [ {kind, id}, … ], offered on /m
    Queue watch: [ { kind: ticket, id: TKT-1001 }, { kind: ticket, id: TKT-1002 } ]
```

Five keys say where the pack's folders are. All are optional, relative to the pack folder, and the default
is used when the key is absent. A folder or file that does not exist is simply skipped.

| Key | Default | What is there |
|---|---|---|
| `sutras` | `sutras` | Sutras (`*.sutra.yaml`) and shared blocks (`*.block.yaml`), scanned recursively; any other `.yaml`, `.yml` or `.sutra.md` file there is reported (`DRS-2004`) |
| `formats` | `config/formats.yaml` | extra named formats (`formats: { age0: { type: number, decimals: 0, suffix: " h" } }`) |
| `semantics` | `config/semantics.yaml` | inference hints: `roles:` (a field-name regex → format, tone, strip weight), `labels:`, `acronyms:` and `idFields:` |
| `samples` | `samples` | sample documents for the built-in `demo` source (`catalog.jsonl`, `<kind>.jsonl`) |
| `about` | `config/about.yaml` | the words of the *About this page* drawer: a sentence per kind, a glossary of fields ([About text and glossary](#about-text-and-glossary)); read when the file exists |

### `columns`: the key fields of a pick list

When a command names several entities (`TRD MX-200000`, `CPTY north`, `TRD PRODUCT_TYPE=REVOLVER`), the user gets a
**pick list**: a table with one row per entity. `columns:` says which fields of each kind appear beside the id,
in order. From `config/packs/trading/pack.yaml` and `config/packs/core/pack.yaml`:

```yaml
columns:                        # KIND: [field, …]  document paths, as in a search ("INDEX.NAME" works too)
  trade:
  - SOURCE_SYSTEM
  - PRODUCT_TYPE
  - TRADE_STATUS
  - TRADE_DATE
  - BUY_SELL_CODE
  - CURRENCY
  - QUANTITY
  - MATURITY_DATE
  - PORTFOLIO
  - COUNTERPARTY
```

```yaml
columns:
  counterparty: [NAME, RATING, SECTOR, COUNTRY_OF_RISK, UEN, NET_MTM]
  book: [NAME, DESK_NAME, TRADE_COUNT, MTM, DV01]
```

With these, `TRD MX-200000 <GO>` on the demo lakes (99 of the 15,000 trades match) shows:

```text
TRD          Source system  Product type   Trade status  Trade date   Buy sell code  Currency  Quantity     Maturity date  Portfolio     Counterparty
MX-20000001  MUREX          IRS_FIXFLOAT   AMENDED       2025-07-25   S              AUD       242,000,000  2032-06-25     BOOK-RATES-3  CP-MERIDIAN-RE
MX-20000002  MUREX          IRS_FIXFLOAT   VALIDATED     2025-02-06   B              EUR       110,000,000  2031-04-15     BOOK-RATES-1  CP-HALCYON
…
```

How the columns of a pick list or search are chosen:

1. the fields the command itself uses, in the order they appear (`TRD CURRENCY=USD order by QUANTITY desc` puts
   *Currency* and *Quantity* first);
2. then the kind's `columns:`, skipping any already shown;
3. if the kind has no `columns:`, the first plain (non-list, non-object) fields of the first document,
   leaving out `id` and fields starting with `_`, up to six columns in all.

Column headings come from the label taxonomy (`labels:` in a pack's `semantics.yaml`, else the name in words: *Buy sell code*),
like every other label. A path may be written `MTM` or `$.MTM`; both mean the same.

Rules:

- `columns:` is optional. In the shipped packs `trade`, `counterparty`, `book`, the world-economy kinds and the news-and-markets kinds
  have it; every other kind uses the automatic columns.
- Choose five to ten fields that tell rows apart at a glance: a type, a direction or status, an amount, a date, an owner.
- A child pack may give a kind of its parent other columns; the more specific pack wins, as for every other
  key ([Inheritance](#inheritance)).
- In a **generated** pack, set the columns in the generator, never in `pack.yaml`
  ([below](#how-the-shipped-packs-are-generated)). For the banking packs that is the `COLUMNS` table in
  `tools/packgen/banking/make_packs.py`; for the packs built with `tools/packgen/common/packbuild.py` it is the
  `columns=` argument of the pack's `PackSpec`, then rerun its `make.py`.

Check what the server uses for a kind:

```bash
curl -s -G $B/search --data-urlencode 'q=BOOK limit 1' | python3 -c 'import json,sys; print(json.load(sys.stdin)["columns"])'
```

You should see `['$.NAME', '$.DESK_NAME', '$.TRADE_COUNT', '$.MTM', '$.DV01']`.

### `pivot`: a Pivot tab on search results

A kind's search results and pick lists can offer the same **Table | Pivot** switch a Sutra gives a table
([USER_GUIDE.md](USER_GUIDE.md#the-pivot-tab-slice-a-table-your-way)). It is offered **only** for the kinds a pack names
under `pivot:`, beside `columns:`; every other kind's results stay a plain list. From `config/packs/trading/pack.yaml`
(generated):

```yaml
pivot:                          # KIND: true, or { fields, rows, columns, values, filters, heat, chart }
  trade:
    fields: [SOURCE_SYSTEM, PORTFOLIO, CURRENCY, PRODUCT_GROUP, PRODUCT_TYPE, BUY_SELL_CODE, TRADE_STATUS, COUNTERPARTY,
             LEGAL_ENTITY, MATURITY_DATE, QUANTITY]
    rows: [PRODUCT_TYPE]
    columns: [SOURCE_SYSTEM]
    values: [{ field: QUANTITY, agg: sum }]
    filters: [PRODUCT_GROUP, TRADE_STATUS]
```

The value is the same as a Sutra's [`pivot` option](RACHANA_REFERENCE.md#pivot-a-pivot-tab-on-a-table-or-ladder),
with one difference: fields are **document paths** (`INDEX.NAME`; `$.` may be written), never
expressions (`bind` is refused). `pivot: { trade: true }` offers the kind's `columns:` as the fields. `false` (or no
entry) offers nothing.

Unlike a panel's pivot, which the browser computes from one document's rows, a search's pivot is computed **on the
server** over every entity the search matches on the business date (not only the page shown), from the day's
**columns**: the fields its store keeps beside each document ([Large kinds](#large-kinds-the-lake-layout)). So choose
fields the connector's `layout.<kind>.columns` lists; the trading pack's pivot fields are all among its trade tables'
columns. A field that no source keeps as a column is marked *doc* in the field list; using it, the server refuses with the reason
and the user may ask for a **document read** instead (at most `drishti.pivot.document-scan`, 20,000 documents; marked
partial beyond). The sample data (the demo plugin) keeps no columns, so on samples every search pivot is a document
read.

Fields the user's role may not see are masked exactly as in a search: grouped under `•••`, never added up.

The server keeps each kind's declaration as `drishti.search.pivot.<kind>`: `true`, or the mapping as JSON. A site may
set it directly in its configuration, as `true` or a JSON string (`drishti.search.pivot.trade: '{"rows": ["PRODUCT_TYPE"]}'`). A
declaration the server cannot read is logged (*no Pivot tab for trade: …*) and that kind offers no Pivot tab; a value
that is neither `true`, `false` nor a mapping stops the pack from loading. In a generated pack, set it in the
generator: `PIVOTS` in `tools/packgen/banking/make_packs.py`.

A Sutra's tables opt in on their own (`pivot:` on the panel). In the banking generators that is `pivot=` on a `Panel`
in `tools/packgen/banking/risk_data.py` (netting sets, books, clearing accounts, desks, collateral); rerun `make_sutras.py`.

### Reserved command words

The console owns six command words: `BI`, `RUPAKA` (its alias), `FORM`, `FUNC`, `WHAT` and `DATAMAP`. A pack may not define a mnemonic with any of those names, in any case: the pack loader
refuses it (`pack 'x' defines the mnemonic 'BI', which is a reserved command word (BI, DATAMAP, FORM, FUNC, RUPAKA, WHAT): choose another`) and `drishti.py pack check` fails the same way. No shipped pack uses them.

### `form`: the search form of a kind

`FORM <pack> <GO>` opens a form for a pack's kinds: one input per field, typed, which the server compiles to the ordinary search
(`TRD where QUANTITY between 1m and 5m ...`) and runs as any search, so masks, kinds per role and packs per user apply. A pack declares the
form of a kind under `form:`; a kind with no entry gets one **inferred**: the kind's search columns, the fields its Sutras' `match.where`
reads and the plain fields of their strip, typed from sample values (numbers, ISO dates, true/false, a short list of repeated words is a
choice) and from the field's name, and labelled with the `term` from `config/about.yaml`. From `config/packs/trading/pack.yaml`:

```yaml
form:
  trade:                              # a kind of this pack; a list of fields, or {calendar: USNY, fields: [...]}
    - { path: TRADE_ID, label: Trade id, type: id, group: Identity, order: 1, help: "One or many: paste a list. MX-2* is any id starting MX-2; Endur ids are numbers." }
    - { path: SOURCE_SYSTEM, label: Source system, type: enum, group: Identity, order: 2, help: "ENDUR, CALYPSO or MUREX: the booking system the trade comes from." }
    - { path: COUNTERPARTY, label: Counterparty, type: enum, group: Identity, order: 3 }
    - { path: PRODUCT_TYPE, label: Product type, type: enum, group: Product, order: 4 }
    - { path: PRODUCT_SUB_TYPE, label: Product sub type, type: enum, group: Product, order: 5, depends: PRODUCT_TYPE }
    - { path: QUANTITY, label: Quantity, type: amount, group: Money, order: 12, operators: [">=", "<=", between, "="], help: "Amounts take k, m and bn: 250k, 1.5m, 2bn." }
    - { path: MATURITY_DATE, label: Maturity date, type: date, group: Dates, order: 14, help: "Relative ranges work here: next 10 business days." }
```

| Key of a field | Meaning |
|---|---|
| `path` | The document path searched (`QUANTITY`, `INDEX.NAME`, `LEGS[0].NOTIONAL`); a `$.` in front is dropped. Required |
| `label` | The heading; by default the path in words (`Product sub type`) |
| `type` | `text`, `number`, `amount`, `date`, `enum`, `id` or `bool` (default `text`) |
| `operators` | Which operators the person may choose, the first being the default; all of the type's when left out (below) |
| `default` | What the input starts with when the form is first opened |
| `order` | Position in the form (lower first). Fields without one follow, in the order written |
| `group` | A heading the field sits under; fields of one group sit together |
| `help` | One line shown under the field |
| `required` | The form cannot be submitted without it |
| `hidden` | Not offered as an input; kept so a saved or shared search can use it. A required hidden field needs a `default` |
| `values` | For an `enum`: the values offered. Left out, the form offers the values found in the data (and the `values` of the field's `about.yaml` glossary entry) |
| `depends` | For an `enum`: another field of the form; only the values that occur with the values chosen there are offered (product sub types of the chosen product types) |

| Type | Input | Operators (first is the default) |
|---|---|---|
| `text` | a text box and a *Match case* box | `equals`, `contains`, `starts` (starts with), `regex` (a regular expression, see `matches` in the [Rachana reference](RACHANA_REFERENCE.md#functions)) |
| `number`, `amount` | one box, or two for `between`; amounts take `250k`, `1.5m`, `2bn` | `=`, `between`, `>`, `<`, `>=`, `<=` |
| `date` | a date picker, two for `between`, or a relative range as text | `on`, `between`, `relative` (`today`, `yesterday`, `last 5 business days`, `next 3 business days`, `last 30 days`, `this week`, `this month`, `month to date`, `year to date`), `before`, `after` |
| `enum` | a tick box for each known value | `in` |
| `id` | a box to paste a list of ids (spaces, commas or lines); `MX-2*` is any id starting `MX-2` | `in` |
| `bool` | Any, Yes, No | `is` |

Business-day ranges (`last 5 business days`) run on the server's calendar, or on the calendar a kind names: `trade: { calendar: USNY, fields: [...] }`.
A field the kind's source does not keep as a column is marked **slower: not indexed** (the documents are read to answer it).

`pack check` reads the section: a field path that is not a path, an unknown `type`, an operator the type lacks (`between` on text), a duplicate
path, `values` on a field that is not an `enum`, a non-number `order`, a kind that is not the pack's, or a key it does not know is an error
that names the kind and field (`pack.yaml form.trade.fields[2] (QUANTITY): type 'money' is not one of text, number, ...`; the keys and rules are in
`tools/packform.py`). In a generated pack, set it in the generator: `TRADE_FORM` and `forms()` in `tools/packgen/banking/make_packs.py`, or
`columns` and `form_overrides` for the packbuild generators (a kind's form is its id and its pick-list columns, typed by name). `pack new`,
`pack make` and *New pack* write a `form:` from the sample documents or schemas. A change proposed from the workbench's Form tab is kept on
the server, once approved, in front of the pack's own `form:` until the pack carries it ([Screen designer, The Form tab](RACHANA_REFERENCE.md#the-form-tab)).

### `history`: a kind over time

`TRD 1000015 30 <GO>` shows the trade over the last 30 business days it has data; `EQ EQ-CSCA 3M` shows the equity's last three months
as a chart with its high, low and change ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#2-history-queries-an-entity-over-time)). **Any kind read from a dated
store has a history without this key**: a table of its top-level fields (the kind's `columns` first, then every other top-level
scalar), its top-level lists and objects shown as counts, the cells that changed from the day before marked. `history:` only shapes
it, per kind, as `form:` does. From the shipped packs:

```yaml
history:
  trade:                                    # config/packs/trading/pack.yaml
    exclude: [_meta]                        # never shown
    ignore-changes: [SOURCE_UPDATE_TIME, INPUT_DATE]   # shown, but a change here is not "a change" (changes only, DIFF)
    parts: [LEGS, EVENTS]                   # top-level lists shown as counts (default: every list or object)
    default-days: 30                        # the range when none is named (at most max-days)
  counterparty-trades: {enabled: false}     # a derived kind: no history (DRS-1012)
  equity:                                   # config/packs/market-data/pack.yaml
    chart: [{path: PRICE, format: price2}]  # the market shape: a chart and the headline numbers (at most 4 fields)
    columns: [PRICE, {path: CHANGE_1D, format: pct2}, {path: MARKET_CAP, format: compact}, BETA, NAME, EXCHANGE, CURRENCY]
    series: {path: HISTORY, date: DATE, value: CLOSE}   # used when no source keeps daily documents
```

| Key | Default | Rule |
|---|---|---|
| `enabled` | `true` | `false`: the kind has no history (`DRS-1012`) |
| `shape` | `market` when `chart` is set, else `document` | |
| `columns` | the kind's `columns`, then every top-level scalar | a top-level path, or `{path \| expr, label, format}` (`expr` is Rachana-EL: `first($.LEGS).NOTIONAL`) |
| `exclude`, `ignore-changes` | none | lists of paths |
| `parts` | every top-level list or object | lists of paths |
| `chart` | none | numeric fields, at most 4; a chart field is always a column |
| `headline` | the first `chart` field | must be one of `chart`: the field the last, change, high, low, range and average are taken on |
| `series` | none | `{path, date, value \| values}`: a list inside the newest document with a date per item |
| `default-days` | `drishti.history.default-days` (30) | 1..`max-days` |
| `max-days` | `drishti.history.max-days` (366) | can only lower the server's |

Unknown keys are warnings; a wrong value is an error (`DRS-2061`) that `sutra lint <pack-dir>` and the deploy check report, while the
running server logs it when the pack loads (at start and at every reload), shows it as the pack's problem on Admin → Packs and in Health
(the pack DEGRADED), and keeps the kind's default history. Every day's row is masked exactly as the kind's view is.

### Keys for packs that inherit and read real data

From `config/packs/counterparty-risk/pack.yaml` (abridged; the file is generated by `tools/packgen/banking/make_packs.py`):

```yaml
pack: counterparty-risk
extends: [trading]              # the packs this one inherits from, in order (the older name "requires" works too);
                                # trading brings core and market-data with it
kinds: [agreement, csa, netting-set, credit-limit, cva, sa-ccr, collateral-balance, margin-call, simm, simulation-run, exposure-path, exposure-result]

connectors:                     # the connectors this pack reads through. Here a mapping NAME: { plugin, enabled?, kinds, settings }:
                                # the names are what the pack needs, the definitions are the pack's TEMPLATES (the site's file wins)
  legal-store:
    plugin: delta               # which source plugin: delta, jdbc, aerospike, kafka, s3, rest, file, feeds, derived, …
    enabled: ${DRISHTI_LAKE_ENABLED:true}    # ${VAR:default} is resolved from the environment
    kinds: [agreement, csa]
    settings:                   # passed to the plugin as-is (each plugin's settings: CONNECTORS.md; credentials only as ${ENV})
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
      domain: legal             # reads <root>/legal/<kind>/
  credit-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [netting-set, credit-limit, cva, sa-ccr]
    settings: { root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: credit }
  collateral-store:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [collateral-balance, margin-call, simm]
    settings: { root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: collateral }

routes:                         # KIND: CONNECTOR — which connector answers each kind
  agreement: legal-store
  netting-set: credit-store
  margin-call: collateral-store
  # … one line per kind

roles:
  credit-risk: { kinds: ["*"], raw: true, calc: true }   # may open every kind, sees every field unmasked, may use Calc
```

#### Connectors: the pack names them, the site owns them

A connector is a **site resource**: one YAML file in `config/connectors/` (`drishti.sources.connectors-dir`), whose file name is the connector's name
(`config/connectors/credit-store.yaml` is `credit-store`). A pack does not carry connection settings that bind a site; it **names** what it reads through
and routes its kinds. The reference for the file is [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).

```yaml
connectors: [credit-store, collateral-store]        # the form for a pack that only names connectors
connector-templates:                                # optional: what to suggest if the site has none yet
  credit-store: { plugin: delta, kinds: [netting-set], settings: { root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: credit } }
routes: { netting-set: credit-store, margin-call: collateral-store }
```

- **`connectors:` is a list of names or a mapping.** A list names site connectors. A mapping (what every pack shipped with Drishti uses, shown above) is read as the names **plus** the
  pack's templates. `connector-templates:` carries templates beside a list; `pack new` writes that form.
- **A template is a suggestion, written once.** When the pack loads, for each connector it names, the server writes `config/connectors/<name>.yaml` from the template
  if no file of that name exists, logs `connector file credit-store.yaml generated from the template of pack 'counterparty-risk'`, and never overwrites an edited file
  afterwards. The generated file starts with `# Generated at first start from the connector template of pack '<pack>'. Edit it freely: an edited file is never overwritten; an unedited one follows the pack's template at an upgrade.`,
  and the server notes what it generated in `<data>/connectors/generated/<name>.properties`. A file the site never edited follows your pack (its stamp line says so: a later version of
  the template replaces it). Once edited the file is the site's: it **replaces** the template, a later version of your pack never changes a value the site set
  and only adds the keys it gained ([CONNECTOR_FILES.md, Upgrades](../connectors/CONNECTOR_FILES.md#upgrades-295)), and the site edits it in an editor or in
  Admin → Connectors, applied live to that one connector. *Reset to pack default* (or `drishti.py connector reset <name>`) writes the pack's current template
  over it, keeping the earlier text in `<data>/connectors/history/`.
- **Kinds go through `routes:`.** You know which kind lives where, so you route it. How a connector file's `kinds:` adds to and narrows the
  routes is stated once, in [CONNECTOR_FILES.md, How packs and files fit together](../connectors/CONNECTOR_FILES.md#6-how-packs-and-files-fit-together). A connector is therefore reusable by other packs.
- **Shared connectors.** Connectors are per data domain, not per pack: the lake is laid out as `<root>/<domain>/<kind>/`, and several packs may name the same
  connector. If two related packs (one `extends` the other) suggest different templates under one name, the more specific pack's template is the one written; two
  unrelated packs suggesting different definitions of one name is an error. Admin → Connectors shows which packs use a connector and asks before the site switches
  it off or deletes it.
- **A name nobody defined is not a failure of the pack.** The pack loads; its kinds answer `DRS-1011` ("connector not configured"), Admin → Health shows
  `connectorsNotConfigured`, and Admin → Packs → Data source offers a **Create** link pre-filled from your template. So give a template for every connector that has a
  sensible default, and for a site-specific one (a customer's own database) name it and document what the site must create.
- **Older site overrides still apply, setting by setting**: environment variables and `drishti.sources.connectors.<name>.…` in the server's own
  configuration still win over a file (and warn), see [CONFIGURATION.md](../admin/CONFIGURATION.md). The recommended way to change a connector is its file.
- **Archives carry names and templates only.** `pack make`, `pack bundle` and Admin → Packs → Deploy archive ship the names and templates, never a site's settings. The deploy preview lists
  the connectors the pack names that the site lacks (`missing`), and the **Create connectors** step writes the files from your templates.

**Checklist for a pack author**

1. List every connector the pack reads through in `connectors:`, and route every kind that is not served by the demo `samples` in `routes:`.
2. Give each a template with settings that work on a developer machine (`${VAR:default}` for paths and URLs, under `${drishti.data.dir:./data}` for folders).
3. Never write a literal credential, not even in a template: use `${ENV_VAR}` (the server refuses a literal in a connector file, and so would any site that tried to keep your template).
4. Do not rename a connector in a later version unless you must: the site's file is named after it, and a new name means a new file to create.
5. Start a scratch server on an empty `config/connectors/` and check that the files are written and that Admin → Health shows no `connectorsNotConfigured` for the pack.
6. Remember that your template is only the first suggestion: changes you make to it in a later version reach no existing site's file (unless the site resets it); say what a site must change in the pack's guide.

### What becomes of each key

The server turns the manifest into ordinary settings. Knowing this helps when you read logs or override something:

| `pack.yaml` | Becomes |
|---|---|
| `kinds` | `drishti.packs.kinds.<pack>[i]` |
| `mnemonics.TKT` | `drishti.commands.mnemonics.TKT.kind` / `.label` |
| `graph.id-patterns` | `drishti.graph.id-patterns[i]` |
| `graph.fields.ASSIGNEE` | `drishti.graph.fields.ASSIGNEE.kind` / `.label` (`scope: pack`: `.from[i]`, the pack's kinds) |
| `graph.badges.agent` | `drishti.graph.badges.agent` |
| `graph.impact.*` | `drishti.graph.impact.follow[i]`, `.measures.<kind>`, `.formats.<kind>` |
| `roles.support` | `drishti.security.roles.support.kinds[i]`, `.raw`, `.author`, `.admin`, `.approve`, `.calc` |
| `connectors.x` (mapping) or `connector-templates.x` | a **template** for `config/connectors/x.yaml` (`plugin`, `enabled`, `kinds`, `settings`), written once if that file does not exist; the file, not the pack, is then the source of the connector's settings |
| `connectors: [x]` (list) | the name `x`: the pack reads through the site connector `x` |
| `routes.kind` | `drishti.sources.routes.<kind>` |
| `columns.trade` | `drishti.search.columns.trade[i]` |
| `pivot.trade` | `drishti.search.pivot.trade` (`true`, or the mapping as JSON) |
| `sutras`, `formats`, `semantics` | added to `drishti.rachana.pack-dirs`, `drishti.rachana.pack-formats-files`, `drishti.inference.pack-semantics-files` |
| `samples` | added to `drishti.sources.plugins.demo.settings.dirs` |

`alerts`, `form` and `console` are read directly from the manifest (by the server's alert suggestions, the form service and the console).

## Part II. Modelling in depth: kinds, data and content

The topics of a pack in depth, after Part I has built one end to end.

### Large kinds: the lake layout

A kind with millions of entities a day (a bank's trades) is stored so that readers never load a whole day. The pack
declares, on its Delta connector, which document fields are also stored as columns, and how the table is sorted. From the
trading pack's `trade-endur` template (`trade-calypso` and `trade-murex` are the same but for `table:`):

```yaml
connectors:
  trade-endur:
    plugin: delta
    enabled: ${DRISHTI_LAKE_ENABLED:true}
    kinds: [trade]
    settings:
      root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}
      domain: trading
      table: { trade: trade_endur }    # the kind's table under <root>/trading/ (default: the kind's name)
      union: true                      # one part of a kind several connectors serve together (next section)
      source-name: trade-endur         # the name in provenance and Health
      layout:
        trade:
          columns: [TRADE_ID, SOURCE_SYSTEM, PRODUCT_TYPE, PRODUCT_SUB_TYPE, PRODUCT_GROUP, TRADE_STATUS, TRADE_VERSION,
                    BUY_SELL_CODE, CURRENCY, QUANTITY, TRADE_DATE, MATURITY_DATE, COUNTERPARTY, LEGAL_ENTITY, PORTFOLIO, TRADER_ID]
          sort-by: id               # each business day sorted by id, cut into files of file-rows
          file-rows: 250000
          row-group-rows: 1000      # the Python writers only; lake build sizes row groups in bytes (DELTA_CONNECTOR.md, Row-group size)
```

- **The writers follow it.** `drishti.py lake build` (and `lake demo`, which calls it for every table) writes the table this way: `id`, the
  document (`doc`), `business_date`, the partition columns it is given (`--partition-by PRODUCT_TYPE` for the trade tables) and one column per
  promoted field (a nested path such as `INDEX.NAME` is the column `INDEX__NAME`; numbers as `float64`, everything else as text). A bank's
  own loader (Spark, Databricks) writes the same columns; `drishti.py lake maintain relayout` rewrites an existing table into the layout, a
  day at a time, and leaves days already in it alone. It finds a table's layout by the table's folder (`trade_endur` is the table of the
  connector whose `table.trade` names it), and keeps a table's own partitions (`business_date` and `PRODUCT_TYPE` for the trade tables).
- **The server uses it.** Opening a trade reads one row group of one file (the id says which). Type-ahead indexes the
  id column alone. Searches, pick lists, the search form's drop-downs, derived kinds and impact (F8) read the columns, over every
  trade of the day, exactly, without parsing a document; a query that uses a field that is not a column reads documents as
  before. Field masks apply to column values exactly as to documents. BI datasets over the kind read the columns too.
- **Health tells you.** A table that lacks a declared column shows in Admin → Health:
  `UP (not laid out as the pack declares: trade (12 of 16 columns); searches read documents)`.
- **Choose the columns** a desk searches and lists by: the pick-list `columns:` of the kind, its `pivot` and `form` fields, the fields in its
  alerts, impact measures and derived kinds, and the link fields reverse lookups follow (`COUNTERPARTY`, `PORTFOLIO`, `LEGAL_ENTITY`). Each
  column costs little: repeated values (books, currencies, statuses) compress to almost nothing.
- **Flattened fields.** A value inside a list (the first leg's notional) cannot be a promoted column as it stands. The pack's `ingest`
  block names such values, and `lake build --pack` writes each as a plain field of the document and a promoted column:

  ```yaml
  ingest:
    trade:
      key: TRADE_ID                    # lake build --pack needs no --key
      flatten:
        NOTIONAL: LEGS[0].NOTIONAL
        INDEX_NAME: LEGS[0].INDEX.NAME
  ```

  A field the document already has is never replaced, and a path a document lacks adds nothing. Searches, Sutras, grouped pages and BI
  then read `NOTIONAL` without the document. A table that exists keeps its columns: the flattened columns appear in tables built from
  scratch (`--mode replace`, or a new lake), so rebuild a table to add them; until every system's table has them, a search over the
  three systems on that field reads documents. (`layout.<kind>.flatten` on a Delta connector is read the same way.)
- **Business dates.** Each table is partitioned by `business_date`; `lake build` writes one date per run (`--as-of`) or the dates a
  field of the documents names (`--date`), and a later run for the same date replaces it. The data-load signal tells a server a date landed
  ([DATA_GUIDE.md](DATA_GUIDE.md#1-telling-drishti-new-data-has-landed)).

The measured effect, for 1,000,000 trades a day, is in [PERFORMANCE.md](../admin/PERFORMANCE.md#a-book-of-a-million-trades-a-day); every option
of `lake build`, `lake demo` and `lake maintain` is in [TOOLS.md](TOOLS.md).

### Several source systems for one kind: the trading pack

Production rarely has one system of record for a kind. The trading pack serves `trade` from three booking systems, kept apart from
the source to the screen, and is the pattern to copy:

1. **One schema.** Every system's trade has the same 47 UPPER_SNAKE top-level attributes, nested the same way (`LEGS[] → INDEX,
   CASH_FLOWS[] → RESETS[]`, `EVENTS[]`), with `SOURCE_SYSTEM` saying where it came from ([Part V. Domain packs: the packs that ship, turning them on, who sees which, Trading](#trading) lists them).
   An extract that names an attribute otherwise is mapped on load (`BMO_LEGAL_ENTITY` is read as `LEGAL_ENTITY`;
   `tools/packgen/trades/schema.py`, `RENAMED`).
2. **Ids unique across systems.** Endur deal numbers (`1000001`…), Calypso `CLY-…`, Murex `MX-…`: an id opens one trade whichever system holds it.
3. **One table and one connector per system.** `<root>/trading/trade_endur`, `trade_calypso`, `trade_murex`, each partitioned by
   `business_date` and `PRODUCT_TYPE`, each read by its own connector with `union: true` and its own `table:`; the partition and promoted
   field names are the same in every table. A system's load (a rebuild, a late file) touches only its own table.
4. **One kind.** All three connectors serve `trade`, so a read finds a trade in whichever holds it, and a search, a type-ahead, the form's
   drop-downs and a BI table read the three together; each row keeps its `SOURCE_SYSTEM` (and the console labels results by source).
   The route names one of them (`trade: trade-endur`); the union is read whole whichever it names.
5. **One Sutra per system and product type**, matching on both fields, with sub types as `when:` panels and the shared panels in
   blocks (step 11, [Many Sutras for one kind](#step-11-a-sutra-the-layout-you-want)); **Sutra coverage** proves every combination in the
   data has exactly one: on the demo lakes `GET /api/v1/packs/trading/coverage?kind=trade` answers `combinations: 132, none: 0, several: 0`,
   each row like `{'values': {'SOURCE_SYSTEM': 'CALYPSO', 'PRODUCT_TYPE': 'ABS', 'PRODUCT_SUB_TYPE': 'Asset-backed security'}, 'count': 96,
   'example': 'CLY-3000223', 'sutras': ['abs'], 'state': 'one'}`.
6. **Derived pages across systems.** `CTRD`, `PTRD`, `LTRD`, `ITRD` are derived kinds over `trade` (the `trade-pages` connector), so a
   counterparty's page gathers its trades from every system ([Derived kinds](#derived-kinds-entities-computed-from-other-kinds)).

The test data in `data/testdata/trades/{endur,calypso,murex}/` (5,000 trades per system over sixty business days: a day-1 snapshot and
daily changes) and `drishti.py lake demo` build the three tables; `tools/packgen/trades/` generates both the test data and the pack's
samples ([How the shipped packs are generated](#how-the-shipped-packs-are-generated)).

### Derived kinds: entities computed from other kinds

A derived kind is a kind no source holds: the server computes it from another kind by grouping and adding up. A
counterparty's trades from every system, a book's P&L from its trades, an agent's open tickets. A pack declares one as a
connector of the built-in plugin `derived` and routes the kind to it. The trading pack ships four (`CTRD`, `PTRD`, `LTRD`, `ITRD`);
the counterparty one:

```yaml
connectors:
  trade-pages:
    plugin: derived
    kinds: [counterparty-trades, portfolio-trades, legal-entity-trades, index-trades]
    settings:
      store: disk               # the pages on disk, the heap keeping each group's totals only (default heap)
      refresh: 60s              # rebuild age, only when a source cannot say its data's version (default 30s)
      counterparty-trades:
        from: trade             # the kind it is built from (read through the normal routing: here the three systems' union)
        group-by: $.COUNTERPARTY  # Rachana-EL over a member: its group, which is the derived entity's id
        id-field: COUNTERPARTY  # a field holding the key besides `id` (default: id only)
        members: TRADE_IDS      # the members' ids, sorted (default: members)
        max-members: 1000       # members listed (and rows); TRADE_COUNT and the totals count every member
        rows-field: TRADES      # where the per-member rows go (default: rows)
        fields:
          TRADE_COUNT: count
          NOTIONAL: sum first($.LEGS).NOTIONAL
          SOURCE_SYSTEMS: distinct $.SOURCE_SYSTEM
          PRODUCT_TYPES: distinct $.PRODUCT_TYPE
          CURRENCIES: distinct $.CURRENCY
          LAST_MATURITY: max $.MATURITY_DATE
          # the payments ladder over every member: PAY_7D, PAY_30D, PAY_90D and their _EVENTS counts
          PAY_7D: "sum sum(flatten($.EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)][?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 7], 'AMOUNT')"
          PAY_7D_EVENTS: "sum size(flatten($.EVENTS)[?nonblank(@.AMOUNT) && nonblank(@.EVENT_DATE)][?days(today(), @.EVENT_DATE) >= 0 && days(today(), @.EVENT_DATE) <= 7])"
          # … PAY_30D, PAY_30D_EVENTS, PAY_90D, PAY_90D_EVENTS
        rows:                   # optional: one row per member, for the page's tables
          TRADE_ID: $.TRADE_ID
          SOURCE_SYSTEM: $.SOURCE_SYSTEM
          PRODUCT_TYPE: $.PRODUCT_TYPE
          NOTIONAL: first($.LEGS).NOTIONAL
          MATURITY_DATE: $.MATURITY_DATE
          INDEX_NAME: nonblank($.LEGS[0].INDEX.NAME, $.LEGS[1].INDEX.NAME, '')
          EVENTS: $.EVENTS
          # … TRADE_STATUS, BUY_SELL_CODE, CURRENCY, COUNTERPARTY, PORTFOLIO
routes:
  counterparty-trades: trade-pages
```

- **Keys.** Each value of `group-by` is one entity (a member whose `group-by` is empty, or whose `where` is false,
  counts in no group). The entity's id is the key; the document has `id`, the `id-field`, `derivedFrom` (shown as *Derived from*), each
  field, the members' ids (at most `max-members`, 1,000 by default), `memberCount` (all of them; *Member count*) and the rows when declared.
  (`derivedFrom` and `memberCount` are written by the engine and keep those names whatever your pack's convention.)
- **Fields.** `count`; `sum`, `avg`, `min`, `max` over an expression (values that are missing or not numbers are
  skipped); `distinct` (the sorted distinct values); `first` (the first member's value, members in id order).
- **`where`** keeps only the members that count (the help-desk pack's `where: $.STATUS != 'Closed'`).
- **Beyond grouping (2.9.2).** `median` is a field aggregate too; `also-group-by` puts a member in further groups (a region, an income
  group, `'ALL'`); `then` works fields out over the page (a ratio, a weighted average, `group(...)`); `sort` orders a list field;
  `tree` builds the group's own hierarchy from a parent column; `unlisted` keeps a page over everything to its figures; `search-fields`
  lets the type-ahead find a group by a page field. The gleif, us-equities and world-economy packs compute LEIG, LEIC, SIC and ECG this
  way ([Part VII. Groupings and analytics, computed](#part-vii-groupings-and-analytics-computed) Part III; every setting in
  [the derived connector's README](../connectors/types/derived/README.md)).
- **Links.** Declare the members field under `graph.fields` (`TRADE_IDS: { kind: trade, label: Trade }`) and the
  members show as links; an `id-field` that is already a link field (`COUNTERPARTY`) links the derived entity to its key's.
- **Business dates.** A picked date is computed from that date's members, so a derived kind has history wherever its
  members do, and *Data for* says which day.
- **`today()`** in a field's expression is the date the pages are computed for: the business date asked for, or today's calendar
  date (in `zone`, `America/New_York`) for the latest pages. The payments ladder counts the events from that date on.
- **Cost.** One computation reads every member once per business date, and is shared by every reader and by all the derived kinds of
  the connector built on the same kind. It reads from the columns where the source keeps every field it needs; otherwise it reads the
  day's documents in one pass (a Delta table streams the date file by file), keeping only the values it needs per member. A source that
  cannot read a day in one pass is read one member at a time, up to `max-scan` (50,000).
- **When it is rebuilt.** Every `version-check` (10 s) the computation asks the sources for their data's version; a new version (a load)
  rebuilds it in the background while the old pages serve. Nothing else rebuilds it: `refresh` is used only for a source that cannot say
  its version.
- **Where the pages live.** `store: heap` (the default) builds every page in memory: right for small books; `max-stream` (100,000)
  bounds the members it keeps, and Health says DEGRADED with a WARN when it cuts. `store: disk` (the trading pack's `trade-pages`)
  writes each member's key and row to a sorted store under `<data dir>/derived/<connector>` and keeps only each group's totals in the
  heap, so the pages are complete at any number of members (1.16 million trades a day in an 8 GB heap). Pages on disk are kept across
  restarts (`pages.json` beside them) and served at once while their data is unchanged.
- **Failures.** Health shows `DOWN` with the reason when a computation fails; a computation that runs out of memory fails cleanly
  (DEGRADED, the old pages serving, no reader waiting). Admin → Caches → Purge (or `drishti.py server caches purge trade-pages`) drops
  the pages; with `store: disk` the latest date is rebuilt from the lake at once. Every setting is in
  [CONFIGURATION.md](../admin/CONFIGURATION.md#derived--kinds-computed-from-other-kinds).
- **Everything else is a kind like any other.** Give it a mnemonic, a Sutra (or let inference lay it out), roles,
  alerts, a form and pick-list columns as usual. It shows in search (`CTRD TRADE_COUNT > 500`) and the type-ahead.
- **Mistakes fail the start of the connector.** A missing `group-by`, an unknown aggregate, a field without its expression, an
  expression that does not parse or a kind built from itself puts the connector under *failed to start* in Admin →
  Health, with the reason.

### Samples

The built-in `demo` source serves every enabled pack's `samples/` folder (when `DRISHTI_DEMO_ENABLED` is `true`, the default), so a pack
works with no database. The format:

`samples/catalog.jsonl` lists every sample. The demo source needs only `kind` and `id`; give `title` and
`subtitle` too, since they are what type-ahead shows. From the trading pack:

```json
[
 { "kind": "trade", "id": "1000001", "title": "1000001", "subtitle": "Trade · ENDUR · ComSwap · CP-HARBORPT · USD 168.0m" },
 { "kind": "trade", "id": "1000002", "title": "1000002", "subtitle": "Trade · ENDUR · ComSwap · CP-LUMEN · USD 22.1m" }
]
```

`samples/<kind>.jsonl` holds the documents of one kind, **one document a line** (JSON Lines), in the catalogue's order;
each is the document itself plus a `_meta` block that becomes its provenance (shown in *How this view was built* and in
`F9`). One line of `config/packs/core/samples/desk.jsonl` (shortened):

```json
{"DESK_ID":"DESK-COMM","NAME":"Commodities","HEAD":"T. Brennan","BOOKS":[{"ID":"BOOK-COMM-1","TRADES":28,"MTM":7760837}, …],"VAR_99":10946782,"MTM":14609595,"LEGAL_ENTITY":"LE-NY", …,"_meta":{…}}
```

A line's id is its catalogue entry: the entry at its position when the document holds that id as a top-level value
(writers keep the catalogue's order), else the first top-level value the catalogue lists as an id of the kind. A kind
whose file would pass 50 MB is split into `samples/<kind>.part-001.jsonl`, `part-002` … (the generators' and `pack new`'s
`--sample-part-mb N`; the readers take the parts in order). The older layout, one `samples/<kind>/<id>.json` file per
document, is still read everywhere (the retired example packs keep it, and packs you wrote that way need no change);
new and shipped packs are written as JSONL (`tools/packgen/common/packsamples.py`, `pack new`, the pack wizard, the workbench's fragments).

| `_meta` key | Meaning |
|---|---|
| `source` | the name shown as the source system |
| `generation` | a version number shown with the source |
| `live` | `true`: the document ticks while someone watches it |
| `walk` | `{ field: step }`: numeric fields, named at the top level or by a dotted path into the document (`LEGS.1.CASH_FLOWS.0.RATE`), that random-walk on each tick by a normal draw whose standard deviation is `step` (usually within a step or two, but unbounded); a step of 1 or more keeps whole numbers. No code needed. |

An entry with no document (no line, or in the older layout no file) is skipped; one whose path would lead outside the
pack's `samples/` folder is skipped too.

**Samples, test data and lakes are three things.** `samples/` is a small, hand-checked set that makes the pack work on its own (765
trades); `data/testdata/` is the committed, production-sized test data the demo lakes are built from (15,000 trades over seven
business days); the lakes are what the connectors read. With the demo source on, a sample answers before the lake for an id both hold.

Realistic samples matter: they should look like what the real system would hold. `tools/samplegen` is a small,
standard-library-only toolkit for that:

| Module | What it gives |
|---|---|
| `ids` | LEI (ISO 17442 check digits), ISIN (Luhn), CUSIP, UTI, UPI, BIC |
| `dates` | business-day calendars (USNY, GBLO, EUTA, JPTO, CATO and joint calendars), tenors, schedules with stubs, day counts (ACT/360, ACT/365F, 30/360, 30E/360, ACT/ACT) |
| `curves` | zero curves with log-linear discount factors, forwards and the published pillar table |
| `legs` | fixed and floating legs with every calculation period: fixing dates, year fractions, fixings or projected rates, amounts, DFs, PVs and status |
| `blocks` | execution, lifecycle and audit, confirmation, clearing, regulatory reporting, settlement instructions, valuation and P&L history |

`tools/samplegen/test_samplegen.py` checks the library against known values.

### Modelling deep documents: legs, cashflows and fixings

A trading system holds a trade as a tree: the **deal**, its **legs**, each leg's **cash flows**, and each floating flow's **resets**
(fixings), with the deal's **events** beside them (tranches with their payments, shipments with their stops and events are the same
shape). Keep that shape in the document: a flat list of every cash flow with a leg number loses what belongs to what, and the Sutra can
show the nesting directly. The trading pack's common schema is the worked example:

| Level | Field of the document | Fields it carries |
|---|---|---|
| deal | the 45 deal attributes, `LEGS`, `EVENTS` | `TRADE_ID`, `SOURCE_SYSTEM`, `PRODUCT_TYPE`, `PRODUCT_SUB_TYPE`, `COUNTERPARTY`, `PORTFOLIO`, `CURRENCY`, the dates … |
| `LEGS[]` | one element per leg: 1 (a cap, a cash deal), 2 (a swap), more (a commodity swap with a fee leg) | `LEG_ID`, `LEG_TYPE` (FIXED, FLOAT, PHYSICAL, OPTION), `LEG_ECONOMIC_TYPE` (Pay, Receive), `NOTIONAL`, `CURRENCY`, `PRICE_UNIT`, `INDEX {NAME, GROUP, SUB_GROUP}`, `RESET_CONVENTION`, `SETTLEMENT_TYPE` |
| `LEGS[].CASH_FLOWS[]` | one element per calculation period (and the notional exchanges of a cross-currency swap) | `FLOW_ID`, `NOTIONAL`, `RATE`, `FLOAT_SPREAD`, `START_DATE`, `END_DATE`, `PAYMENT_DATE`, `RATE_DETERMINATION_DATE`, `STRIKE_PRICE` |
| `LEGS[].CASH_FLOWS[].RESETS[]` | one element per observation of the index in the period: one for a term rate, a business day each for a commodity average | `RESET_ID`, `RESET_DATE`, `RATE` … |
| `EVENTS[]` | cash and lifecycle events, each naming its leg by `LEG_ID` (blank for the deal's own) | `EVENT_ID`, `EVENT_TYPE`, `PAYMENT_TYPE`, `EVENT_DATE`, `AMOUNT` (signed: paid is negative), `CURRENCY` |

Rules that keep such a document honest (the generators' tests, `tools/packgen/trades/test_trades.py`, check them on every product and sub type
they book):

1. **Write what the extract writes.** Missing values as the system spells them (mostly `""`, a few `null`, `0` for amounts), numbers that
   arrive as text left as text; the Sutras read them with `nonblank(...)` rather than assuming a value. A real extract can have zero
   notionals and empty events: the screen must still read.
2. **Fixed flows simply have no resets.** Do not invent observations for a fixed flow; a floating flow's rate lies among its resets
   (the average, for a commodity average).
3. **Signs mean something.** An event's `AMOUNT` is negative when the bank pays it; the payment ladder nets by date on that sign.
4. **Every leg and flow has its id** (`LEG_ID`, `FLOW_ID`, `RESET_ID`), so events, Compare and the legs-side-by-side tabs can name them.
5. **Size.** A deep document is bigger than a flat one (a twelve-month commodity swap with daily resets is tens of kilobytes). That is fine
   for a view; keep the lake's rows to the sizes in [the lake layout](#large-kinds-the-lake-layout) and local test data small (10,000
   trades or fewer on a laptop).

Show it as the trading pack's `legs` block does: one `tabs` panel with `each: $.LEGS` and `layout: columns` (the legs side by side, each
holding its terms, its notional profile, its resets and its cash flows as a tree table that opens to the resets), as in
[the Rachana reference](RACHANA_REFERENCE.md#panels-inside-a-tab); and make the demo tick a nested cell with a path in `_meta.walk`
(see [Samples](#samples)). Without a Sutra, inference lays such a document out the same way. For more than a handful of legs, tabs stop
being the right control: use a `table` of the legs with `children`.

#### Sutras over deep documents

(This was NESTED_DOCUMENTS.md.) Real documents are trees. A trade from a booking system holds legs that each hold their own list of cash flows, each
flow its own list of resets (the fixings of a floating rate or price), and a list of events, cash and lifecycle, each
naming the leg it belongs to. Rachana reads any depth with the same short paths.

Every example below runs against the trading pack's interest rate swap **`MX-20000001`**, booked in Murex. It is in the
common schema every source system writes (Endur, Calypso and Murex alike: 47 upper-snake attributes, `LEGS[]` with
`INDEX` and `CASH_FLOWS[]` -> `RESETS[]`, and `EVENTS[]`; [Part V. Domain packs: the packs that ship, turning them on, who sees which](#trading)). To follow along:

1. Open **Build → New screen**, bring the stored entity `trade` `MX-20000001`, and start from *an empty Sutra*.
2. Open the **YAML** tab to write, and the **Design** tab to see the result; the left pane's *shape* lists every path of
   the document while you write them.

The path, filter and function basics (`$`, `@`, `[?...]`, `size`, `first`) are taught in
[Rachana-EL step by step](/help/sutra-developer-guide#3-rachana-el-step-by-step); this tutorial is about how they behave
**deep in a tree**.

##### 1. The shape of the document

This is the part of `MX-20000001` that the examples read (abridged; `…` marks what is left out):

```json
{
  "SOURCE_SYSTEM": "MUREX", "TRADE_ID": "MX-20000001", "TRANSACTION_ID": "20000001002", "TRADE_VERSION": "2",
  "PRODUCT_TYPE": "IRS_FIXFLOAT", "PRODUCT_SUB_TYPE": "Interest rate swap (fixed/float)",
  "COUNTERPARTY": "CP-MERIDIAN-RE", "COUNTERPARTY_UEN": "UEN0100407", "COUNTERPARTY_COUNTRY_OF_RISK": "BM",
  "LEGAL_ENTITY": "LE-NY", "TRANSIT": "00107", "PORTFOLIO": "BOOK-RATES-3", "TRADER_ID": "TRDR-ASHAH",
  "TRADER_FIRST_NAME": "A.", "TRADER_LAST_NAME": "Shah", "SOURCE_UPDATE_TIME": "2026-09-30T18:30:00Z",
  "LEGS": [
    { "LEG_ID": 1, "LEG_TYPE": "FIXED", "LEG_ECONOMIC_TYPE": "Receive", "NOTIONAL": 242000000.0, "INDEX": { "NAME": "", … },
      "CASH_FLOWS": [
        { "FLOW_ID": 20000001001, "RATE": 0.040829, "START_DATE": "2025-07-28", "PAYMENT_DATE": "2026-06-29", "RESETS": [] },
        { "FLOW_ID": 20000001002, "PAYMENT_DATE": "2027-06-29", … }, …
      ] },
    { "LEG_ID": 2, "LEG_TYPE": "FLOAT", "LEG_ECONOMIC_TYPE": "Pay", "INDEX": { "NAME": "AONIA", "GROUP": "Rates", "SUB_GROUP": "Monthly" },
      "CASH_FLOWS": [
        { "FLOW_ID": 20000001008, "RATE": 0.035377, "PAYMENT_DATE": "2025-09-29",
          "RESETS": [ { "RESET_ID": "R-1", "RESET_DATE": "2025-07-28", "RATE": 0.035376 }, { "RESET_ID": "R-2", … } ] },
        … 28 periods … ] }
  ],
  "EVENTS": [
    { "LEG_ID": "", "EVENT_TYPE": "BOOKED", "EVENT_DATE": "2025-07-25", "AMOUNT": "" }, …,
    { "LEG_ID": "", "EVENT_TYPE": "MATURITY", "EVENT_DATE": "2032-06-25", "AMOUNT": "" },
    { "LEG_ID": 1, "EVENT_TYPE": "CASH_SETTLEMENT", "PAYMENT_TYPE": "Interest", "EVENT_DATE": "2026-06-29", "AMOUNT": 8987301.85 }, …
  ]
}
```

Missing values are written the way extracts write them: mostly an empty text `""` (the fixed leg's `INDEX.NAME`, a deal event's
`LEG_ID` and `AMOUNT`), sometimes `null`, and an Endur deal may leave half its fields blank. Section 3 shows how to read past them.

##### 2. Paths go as deep as the data

A path starts at `$` (the document), walks into objects with `.NAME` and into arrays with `[n]` (counting from 0).

| Path | Reads, on MX-20000001 |
|---|---|
| `$.COUNTERPARTY` | *CP-MERIDIAN-RE* (a reference: `link($.COUNTERPARTY, 'counterparty')` opens it) |
| `$.LEGS[1].INDEX.NAME` | *AONIA*: array, object, field |
| `$.LEGS[0].LEG_ECONOMIC_TYPE` | *Receive* (the first leg) |
| `$.LEGS[-1].LEG_TYPE` | *FLOAT* (negative numbers count from the end) |
| `$.LEGS[0].CASH_FLOWS[1].PAYMENT_DATE` | *2027-06-29*: array, object, array, field |
| `$.LEGS[1].CASH_FLOWS[0].RESETS[0].RATE` | *0.035376*: four levels down, the first fixing of the floating leg |
| `size($.LEGS[1].CASH_FLOWS)` | *28* (how many periods the floating leg has) |
| `size(flatten($.LEGS[*].CASH_FLOWS))` | *35* (every period of every leg, as one list) |

Try one: add this to the strip in the YAML tab and press **Ctrl+Enter**.

```yaml
  - { label: Trader, bind: "$.TRADER_FIRST_NAME + ' ' + $.TRADER_LAST_NAME + ' (' + $.TRADER_ID + ')'" }
```

You should see *Trader: A. Shah (TRDR-ASHAH)*.

!!! note "Missing branches are empty, not errors"
    A path that runs into a branch the document does not have (`$.LEGS[5]` here; `$.EVENTS` on a deal whose extract
    left the key out) is simply empty. The cell shows a dash, and a panel with nothing to show says
    *No data available*. Nothing fails, and the rest of the view renders.

##### 3. Pick elements by content with filters

Positions change from document to document; content does not. The filter `[?condition]` (taught in
[the guide](/help/sutra-developer-guide#filters)) is what makes a path into a tree robust. Where a position would break:

| Expression | Reads |
|---|---|
| `$.LEGS[?@.LEG_TYPE == 'FLOAT'][0].INDEX.NAME` | *AONIA*: the floating leg, wherever it sits |
| `$.LEGS[?@.LEG_ECONOMIC_TYPE == 'Pay'][0].LEG_ID` | *2* (the first leg we pay) |
| `size($.EVENTS[?!nonblank(@.LEG_ID)])` | *7* (the deal's own events: no leg) |
| `size(flatten($.LEGS[1].CASH_FLOWS[*].RESETS)[?!nonblank(@.RATE)])` | *75* (fixings of the floating leg not yet known) |

`nonblank(x, …)` gives the first argument that has a value, skipping `""` and spaces as well as null: it is how a Sutra reads an
extract that spells "nothing" as empty text. `coalesce` keeps an empty text, so prefer `nonblank` for fields like these.

##### 4. Tables over nested arrays

`rows` can point at an array anywhere in the tree. Inside the rows, `@` is the current row:

```yaml
- id: flows
  kind: ladder
  title: Fixed leg cash flows
  rows: $.LEGS[0].CASH_FLOWS          # an array inside an array element
  highlight: "@.PAYMENT_DATE > '2026-09-30' && #index > 0 && $.LEGS[0].CASH_FLOWS[#index - 1].PAYMENT_DATE <= '2026-09-30'"   # the next payment
  columns:
    - { label: Start, bind: "@.START_DATE", fmt: date }
    - { label: Payment, bind: "@.PAYMENT_DATE", fmt: date }
    - { label: Rate, bind: "@.RATE", fmt: pct4 }
    - { label: Notional, bind: "@.NOTIONAL", fmt: amount0 }
```

Paste it into the `panels:` list and preview. You should see seven rows from *2026-06-29* to *2032-06-29*, the
*2027-06-29* row highlighted (the first payment after the samples' date).

To list only what is still to come, filter the rows (`today()` is the business date the view is built for):

```yaml
  rows: "$.LEGS[0].CASH_FLOWS[?days(today(), @.PAYMENT_DATE) >= 0]"
```

##### 5. One tab per element, each with its own nested rows

A `tabs` panel repeats a layout for every element of an array. Inside the body, `@` is that element, including
everything nested inside it:

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.LEGS
  layout: columns
  tabTitle: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_ECONOMIC_TYPE + ' ' + @.LEG_TYPE"
  body:
    kind: kv
    columns:
      - { label: Index, bind: "nonblank(@.INDEX.NAME, 'Fixed')" }        # the fixed leg's index is empty text
      - { label: Periods, bind: "size(@.CASH_FLOWS)" }
      - { label: Next payment, bind: "@.CASH_FLOWS[?@.PAYMENT_DATE > '2026-09-30'][0].PAYMENT_DATE", fmt: date }
      - { label: Fixings, bind: "size(flatten(@.CASH_FLOWS[*].RESETS))" }
```

You should see two columns. *Leg 1 · Receive FIXED*: Index *Fixed*, Periods *7*, Next payment *2027-06-29*, Fixings *0*.
*Leg 2 · Pay FLOAT*: Index *AONIA*, Periods *28*, Next payment *2026-12-31*, Fixings *90*.

A tab can hold whole panels as well, with `panels:`; each is read with `@` the tab's element. Here each leg shows its notional
profile (a step line), its fixings (a line) and its own cash flows, each flow opening to its resets, in a column for every leg
whatever their number (this is how the trading pack's deal screens show legs side by side):

```yaml
- id: legs
  kind: tabs
  title: Legs
  each: $.LEGS
  layout: columns
  tabTitle: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_TYPE"
  panels:
    - id: leg-notional
      kind: line
      title: Notional profile
      rows: "@.CASH_FLOWS"
      x: START_DATE
      y: NOTIONAL
      step: true
    - id: leg-fixings
      kind: line
      title: Fixings
      rows: "flatten(@.CASH_FLOWS[*].RESETS)[?nonblank(@.RATE)]"
      x: START_DATE
      y: RATE
    - id: leg-flows
      kind: table
      rows: "@.CASH_FLOWS"
      children: "@.RESETS"          # a flow that has resets opens to them (omit this for a plain table)
      columns:
        - { label: Flow / reset, bind: "nonblank(@.FLOW_ID, @.RESET_ID)" }
        - { label: Payment, bind: "nonblank(@.PAYMENT_DATE, @.RESET_DATE)" }
        - { label: Rate, bind: "@.RATE", fmt: pct4 }
```

You should see two columns, *Leg 1 · FIXED* and *Leg 2 · FLOAT*: the first with a flat notional line, no fixings (*No data
available*: a fixed leg has none) and 7 flows; the second with its notional line, the AONIA fixings known so far, and 28 flows, each
opening (▸) to its two resets. `children` makes a table a tree: a row whose `@.RESETS` is a list gets a ▸ that opens them. A panel
inside a tab is a `kv`, `status`, `table`, `ladder`, `hbar`, `markdown`, `metric`, `line`, `area` or `timeline`, needs an `id`, and
cannot have `span`, `height` or a Pivot tab. [Panels inside a tab](/help/rachana-reference#panels-inside-a-tab) lists the rules.

##### 6. A whole Sutra over a nested trade

This Sutra is an operations view of any trade: who booked it where, its version, its parties, its events and what is paid
when. In the workbench, paste the YAML below into the YAML tab of the design you started above and preview it. `description` and
`notes` at the top and bottom are plain text for the people who maintain the layout; they do not change the view.

```yaml
rachana: 1
sutra: trade-operations
version: 1
description: Operations view of any trade, from any source system, read from its legs, flows, resets and events.
match: { kind: trade, priority: 1 }
title: { pill: "Trade · operations", id: $.TRADE_ID, with: "link($.COUNTERPARTY, 'counterparty')" }
strip:
  - { label: Product, bind: $.PRODUCT_SUB_TYPE }
  - { label: Source, bind: $.SOURCE_SYSTEM }
  - { label: Transaction, bind: $.TRANSACTION_ID }
  - { label: Version, bind: "nonblank($.TRADE_VERSION, '–')" }
  - { label: Status, bind: $.TRADE_STATUS, tone: status }
  - { label: Legal entity, bind: "link($.LEGAL_ENTITY, 'legal-entity')" }
  - { label: Transit, bind: $.TRANSIT }
  - { label: Updated, bind: "nonblank($.SOURCE_UPDATE_TIME, '–')", emphasis: true }
panels:
  - id: parties
    kind: kv
    title: Parties and booking
    key: F2
    columns:
      - { label: Counterparty, bind: "link($.COUNTERPARTY, 'counterparty')" }
      - { label: UEN, bind: "nonblank($.COUNTERPARTY_UEN, '–')" }
      - { label: Country of risk, bind: "nonblank($.COUNTERPARTY_COUNTRY_OF_RISK, '–')" }
      - { label: Portfolio, bind: "link($.PORTFOLIO, 'book')" }
      - { label: Trader, bind: "link($.TRADER_ID, 'trader', $.TRADER_FIRST_NAME + ' ' + $.TRADER_LAST_NAME)" }
      - { label: Internal or external, bind: $.INTERNAL_EXTERNAL_CODE }
  - id: history
    kind: ladder
    title: Lifecycle
    key: F3
    rows: "$.EVENTS[?!nonblank(@.LEG_ID)]"
    highlight: "#index == size($.EVENTS[?!nonblank(@.LEG_ID)]) - 1"
    columns:
      - { label: Event, bind: "@.EVENT_TYPE" }
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }
  - id: cash
    kind: table
    title: Cash events
    rows: "$.EVENTS[?nonblank(@.AMOUNT)]"
    columns:
      - { label: Leg, bind: "@.LEG_ID" }
      - { label: Date, bind: "@.EVENT_DATE", fmt: date }
      - { label: Type, bind: "@.PAYMENT_TYPE" }
      - { label: Amount, bind: "@.AMOUNT", fmt: signed2, tone: sign, total: true }
  - id: by-year
    kind: hbar
    title: Cash by year (paid − / received +)
    area: right
    rows: "group($.EVENTS[?nonblank(@.AMOUNT)], 'EVENT_DATE', 'AMOUNT', 'year')"
    fmt: signed0
    tone: sign
  - { id: built, kind: provenance, title: How this view was built }
keys: { F7: "link($.COUNTERPARTY, 'counterparty-trades')", F9: raw }
notes: |
  Priority 1, so any product's own Sutra wins; this one is for operations staff who open a trade by hand.
  Every path reaches into a nested branch; a trade without one of them shows a dash, not an error.
```

You should see:

- the strip: *Product Interest rate swap (fixed/float)*, *Source MUREX*, *Transaction 20000001002*, *Version 2*, *Status AMENDED*,
  *Legal entity LE-NY* (a link), *Transit 00107*, and *Updated 2026-09-30T18:30:00Z* highlighted;
- **Parties and booking** (F2), with *Counterparty*, *Portfolio* and *Trader* as links you can click;
- **Lifecycle** (F3): *BOOKED*, *CONFIRMED*, *CLEARED*, *AMENDED*, *CASH_SETTLED*, *NEXT_PAYMENT*, *MATURITY*, the last highlighted;
- **Cash events**: the 36 settlements of both legs with their total, and on the right **Cash by year**, received in green and
  paid in red.

Why `priority: 1`: the product Sutras (such as `irs-fixfloat`) match with priority 10, so they stay the default
view of a trade. Saved as it is, this operations Sutra lays out only trades that no product Sutra matches. To make
it the default where operations staff work, give it a higher priority in that installation.

##### 7. When inference meets a tree

With no Sutra at all, inference also walks the tree. Open `trade` / `MX-20000001` with *auto-design* as the start and
you get, among others:

| Part of the document | Inferred panel |
|---|---|
| `LEGS` (two similar objects) | `tabs` *Legs* |
| `LEGS[0].CASH_FLOWS` (an array inside the first leg) | a `table` of the first leg's cash flows |
| `EVENTS` (rows with a date) | a `table` or `ladder` of the events |
| the deal's own fields | `kv` panels |

Upper-snake names read as words in the labels the inference writes (`MATURITY_DATE` is *Maturity date*). Keep what is useful,
delete the rest, and add labels and keys. See [Inference](/help/inference) for the rules.

!!! tip "Finding the path"
    Press **F9** on any view to see its raw JSON. In the workbench, the **shape** in the left pane lists the document the Sutra
    reads. A path is exactly the keys you see there, joined with dots, with `[n]` for list positions.

### Vocabulary: labels, formats and inference hints

Two optional files teach every screen the pack's words. They are read when the pack loads (and again whenever packs change, with no restart) and join the site's configuration at
the lowest precedence ([What becomes of each key](#what-becomes-of-each-key)); a more specific pack's entry wins over
a parent's ([Inheritance](#inheritance)).

`config/formats.yaml` names formats a Sutra (or the vocabulary) can use as `fmt:`:

```yaml
formats:
  age0: { type: number, decimals: 0, suffix: " h" }       # 30 → "30 h"
```

Every key of a format, and the built-in ones, is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) (Formats).

`config/semantics.yaml` has four parts:

| Part | What it does | Example |
|---|---|---|
| `roles` | a field-name **regex** (case ignored) → how *inference* shows such fields (`role`, `fmt`, `tone`, `weight` for the strip, `dateOnly`). Tried before the core roles, in order | `{ role: age, pattern: "HOURS$", fmt: age0, weight: 80 }` shows `AGE_HOURS` as `30 h` |
| `labels` | field → label, **everywhere** (strips, tables, pick-list headings, a Sutra that gives no label). A field with no label is put in words (`OPEN_TICKETS` → *Open tickets*) | `AGE_HOURS: Age (h)` |
| `acronyms` | words kept in capitals when a field is put in words (`NET_MTM` → *Net MTM*, not *Net mtm*) | `core` lists `MTM`, `PV`, `DV01`, `CVA`, `ISDA` … |
| `idFields` | document fields that hold the id, so inference leads with it | `["TICKET_ID", "AGENT_ID", "CLIENT_ID"]` |

The roles only steer **inference**: a Sutra states its formats itself. Labels reach both. How inference scores a field and
how the hints fit in is in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#inference-the-screens-drishti-draws-with-no-sutra). Mistake to avoid: a `labels` entry is global
to the server, so `STATUS: Ticket status` would relabel `STATUS` in every other pack that is loaded. Name labels for
fields that are yours (`AGE_HOURS`), and give a Sutra's strip entry an explicit `label:` for the shared ones.

### About text and glossary

The *About this page* drawer (`?` on a view; [how a user reads it](USER_GUIDE.md#about-this-page)) always shows where the
data came from and why the layout is what it is: those come from the engine. What only **you** can say is what a
kind is and what its figures mean. That is the pack's `config/about.yaml`, found beside `formats.yaml`,
`semantics.yaml` and `help.yaml`. The file is optional: without it the drawer still works.

![The drawer on an economy, with the world-economy pack's text](img/about/04-economy.jpg)

#### The file

From `config/packs/market-risk/config/about.yaml` (generated; shown shortened):

```yaml
about: 1                           # the file's version, required
vocabulary:                        # entries to reuse, each under a name of your choosing
  var99:
    term: "Value at risk, 99%, 1 day"
    means: "The loss the portfolio is expected not to exceed on 99 of 100 trading days, read from the 1st percentile of the revalued scenario P&L (historical simulation)."
    unit: "USD"
    sign: "A loss, written as a positive number."
kinds:
  var:                             # a kind of this pack, or of a pack it extends
    title: Value-at-risk result
    about: >-                      # the sentence at the top of the drawer: a ${...} template over the document
      ${$.RESULT_ID} is a 1-day 99% historical VaR for ${coalesce($.DESK_NAME, $.DESK, 'this portfolio')}:
      ${fmt($.VAR_99, 'compact')} USD, ${fmt($.VAR_99 / $.LIMIT, 'pct0')} of its ${fmt($.LIMIT, 'compact')} limit,
      with ${$.EXCEPTIONS} exception(s) in 250 days.
    guide: market-risk             # slug[#anchor] of the pack's guide, linked from the drawer
    glossary:                      # one entry per field, keyed by its path (dots between names)
      VAR_99: { use: "var99" }     # reuse a vocabulary entry
      EXCEPTIONS:
        term: "Backtesting exceptions"
        means: "The days in the last 250 on which the day's loss was larger than the 99% VaR. Basel's traffic lights count them to judge the model."
        unit: "days"
        note: "0-4 is the Basel green zone, 5-9 amber and 10 or more red."
```

#### Keys and rules

| Key | Where | Type | Rule |
|---|---|---|---|
| `about` (file) | top | integer | required, `1` (`DRS-2040` otherwise) |
| `vocabulary.<name>` | top | entry | `<name>` is a name without dots |
| `kinds.<kind>` | top | map | the kind must belong to this pack or a pack it `extends` (`DRS-2041`) |
| `title` | kind | text | plain |
| `about` | kind, `panels.<id>` | template | `${...}` expressions of the Rachana expression language, compiled when the pack loads (`DRS-2042`, with line and column) |
| `guide` | kind | `slug[#anchor]` | a help-centre guide; the console's help-link test checks it |
| `glossary.<key>` | kind | entry or `{ use: <name> }` | the key is a field path, dots between names, arrays not a step (`HISTORY.DATE`; the rule of `drishti.security.redact`); `use` must name a `vocabulary` entry visible to this pack (`DRS-2043`) |
| entry: `term`, `means`, `unit`, `sign`, `note` | entry | plain text | at most `drishti.about.max-text` (600) characters each (`DRS-2044`); never evaluated, so no `${}` |
| entry: `formula` | entry | plain text | shown as written; a derived kind's own fields get their formula automatically |
| entry: `values` | entry | map | the meaning of each value of an enumerated field, shown for the value on the page |
| `panels.<id>` | kind | `{ about }` | a note shown for that panel; the id should exist in some Sutra of the kind (`DRS-2046` warns otherwise) |

Unknown keys are errors (`DRS-2040`): the parser is strict, so a typo is found when the pack loads. A broken entry is
left out (the view and the drawer still work) and is reported with file, line and column on the admin *Sutras* page's
problems list and in `GET /api/v1/sutras/problems`, under the key `<pack>/<file>`.

#### What the template sees: your readers' view, not the stored document

`about` text is rendered over the document **as the person asking may see it**. A field named in
`drishti.security.redact` reads `•••` in the sentence for a role without `raw`; do not try to work around it, and do not
put values in a glossary entry (entries are never evaluated). An expression that fails renders `—` and is counted
(`drishti.explain.template-errors`), never shown as an error. A rendered text is capped at `drishti.about.max-rendered`
(1,000) characters. Formats are the pack's named formats (`fmt($.X, 'compact')`, your `formats.yaml` names too).

![For a viewer the masked limit reads as the mask in the sentence (a demo: shipped setups do not mask `LIMIT`)](img/about/05-viewer-masked.jpg)

#### Inheritance: `extends`

The catalogue is built from every loaded pack's file in the order `extends` defines, **most specific pack first**
(child first, parents right to left: the same order as `semantics.yaml`). A child can replace any entry by its key
(write the whole `{ term, means }` again) and cannot delete one. There is no inheritance between kinds. Put a shared
word in the lowest pack that owns the concept (`MTM` in `trading`, which `market-risk` and `counterparty-risk` reach
through `extends`), as a `vocabulary` entry, and point to it with `use:`.

How a field's entry is found ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#about-this-page-the-design-of-context-help)): the kind's `glossary`
in the most specific pack, then a `vocabulary` entry with the field's last name, then, for a derived kind, the entry
generated from its definition ("Sum of `NOTIONAL` over the trades of the counterparty"), then nothing: the field shows no hint. The
explain answer (`GET /api/v1/views/<kind>/<id>/explain`) lists each shown field's entry with its `origin` (`helpdesk:glossary.PRIORITY`).

#### Translations

A pack can say the same things in another language: `config/about.<lang>.yaml` beside `about.yaml` (`<lang>` is a language
tag such as `fr` or `fr-CA`). It has the same shape and the same strict parse (`DRS-2040` to `DRS-2044`, reported under
`<pack>/about.fr.yaml`) and is laid over the English file **key by key**: whatever it does not translate stays English,
so a first translation can be one kind, or one sentence. For a regional tag the plain language fills in before English
(`fr-CA`, then `fr`, then `en`). The language of an answer is `?locale=` on the explain request, else the user's *Language of
help text* on the account page, else the browser's `Accept-Language`; the first of those with an overlay wins, and the
answer's `locale` says which was used. Numbers in a template use the pack's named formats and are not localised. The
shipped example is `config/packs/market-risk/config/about.fr.yaml` (the VaR page in French).

#### Generated packs

A pack built on **public data** (world-economy, news-and-markets, market-data) has its own refresh script in
`tools/publicdata/` (one a pack: fetch with a polite cached client, normalise, write, then the pack's check; licences and the
internal-only rule in each source class). To add a source or a script for a new pack, see [DATA_GUIDE.md](DATA_GUIDE.md#adding-a-source).

The shipped packs are generated (see [How the shipped packs are generated](#how-the-shipped-packs-are-generated)).
Their `config/about.yaml` (and any `about.<lang>.yaml`) is copied from a hand-kept source in the generator folder
(`tools/packgen/banking/about/<pack>.yaml`, `tools/packgen/<generator>/about.yaml`) by `make_packs.py` / `packbuild.py`: edit
that source and regenerate, never the generated file; `--check` fails when they differ. `sutra test` (with `help: { about: true }` in
`expect.yaml` and a `tests/help-coverage.txt` ratchet in the pack) keeps every shown field explained.

#### Check it

- Load the pack: a file with a problem lists it (codes `DRS-2040` to `DRS-2044`, [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md)).
- Open an entity and press `?`: the first section is your sentence, filled in. Try it as a user whose role is not
  `raw`: masked fields read `•••`.
- `sutra lint config/packs/<pack>` warns (`DRS-2046`, `DRS-2047`) about a `panels.<id>` that matches no panel and about each field a Sutra
  shows that has no glossary entry (`ticket.v1.sutra.yaml:15 warning DRS-2047 field 'ASSIGNEE' is shown but has no glossary entry
  (kinds.ticket.glossary in config/about.yaml, or a vocabulary entry)`); `sutra test` reports *help coverage n/m* per Sutra;
  `drishti.py pack about-check` lists the gaps per Sutra and exits `1` while any remain; `pack check --strict` fails on them.
  `expect.yaml` takes `help: { coverage: 0.9, about: true }` (the share of shown fields with an entry, and that `about` renders without an error).
- The Build workbench has an **About** tab to write and preview this text over your samples: the card of the previewed entity, the problems with their lines and the lint warnings, kept with the design and exported as `config/about.yaml` in the pack fragment ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#the-about-tab)). It is a draft until you put the file in the pack: approving the design's Sutra does not make the About text live.

### Guides and help

A pack's help appears in the help centre (`/help`) next to the core guides. `config/help.yaml`, from the help-desk pack:

```yaml
guides:
  - { slug: helpdesk-pack, category: start, title: "The help-desk pack", kind: guide, icon: headset,
      file: guides/helpdesk.md, summary: "Tickets, agents and clients." }
contextual:                     # optional: which guide F1 opens on a screen
  terminal: helpdesk-pack
```

The shipped packs file their guides under `category: packs` (*Help → Domain packs*): `core` has `core` and `banking-data-model`, `trading`
has `trading` and `trading-products`, and so on.

| Key | Meaning |
|---|---|
| `slug` | the guide's address: `/help/helpdesk-pack`. Must be unique across all packs. |
| `category` | where the card goes: `start`, `packs`, … (the categories in `drishti-console/config/help.yaml`) |
| `title`, `summary`, `icon` | the card. `icon` is a Bootstrap Icons name without the `bi-` prefix. |
| `kind` | `guide` or `tutorial`: the badge shown beside the title |
| `file` | the Markdown file, relative to the pack folder |

Screens for `contextual` include `landing`, `terminal`, `view`, `studio`, `workspace`, `monitor`, `alerts`,
`impact`, `admin`, `account` and `help`.

Write the guide in Markdown with the copyright header comment at the top. Describe the domain (what each kind
is, how they link, where the data comes from) rather than listing sample records, and include a **Finding
things** section like the one in [Part V. Domain packs: the packs that ship, turning them on, who sees which](#trading), so readers know the commands work on their own data too. Links to other help documents with a relative
path (`[Sutra guide](SUTRA_DEVELOPER_GUIDE.md)`) open inside the help centre.

### Facts for Home

A pack can give the console's Home page **Did you know** facts ([USER_GUIDE.md](USER_GUIDE.md#home-the-fact-of-the-day)): surprising
things its data says on the business date. They live in the pack, in `config/facts.yaml` (no `pack.yaml` key: the console reads the
file when it is there), and carry no numbers: each fact fetches what it needs from the server's APIs as the user, picks the row that
makes the point, and says when the point holds. The shipped examples are `config/packs/market-risk/config/facts.yaml`,
`counterparty-risk/config/facts.yaml` and `funds/config/facts.yaml`; `drishti-console/core/facts.py` reads and computes them.

```yaml
facts: 1
items:
  - id: book-over-limit-desk-within        # letters, digits, hyphens; unique in the file
    domain: Market risk                    # the tile's label
    needs: [counterparty-risk]             # other packs it needs (its own pack always); one off for the user: not shown
    steps:                                 # run in order; each binds its result to the name in `as`
      - { as: tree, var: /tree, params: { hierarchy: LEGAL } }      # GET /api/v1/var/tree?hierarchy=LEGAL&asOf=<date>
      - as: book                                                    # the best row: where filters, order ranks (largest first)
        pick: tree.nodes
        where: "book.limitUse > 1 and find(tree.nodes, 'id', book.parent).limitUse <= 1"
        order: book.limitUse
      - { as: parent, let: "find(tree.nodes, 'id', book.parent)" }  # a value
    when: "book.limitUse > 1"              # what makes it surprising; false: not shown today
    text: "{book.name} is at **{book.limitUse|pct}** of its VaR limit."       # **…** is drawn highlighted
    why: "Its VaR is {book.var|money} against {book.limit|money}; …"
    picture: { type: bars, caption: "Share of the limit used, {asOf}",
               bars: [ { label: "{book.name}", value: book.limitUse, format: pct, tone: alert },
                       { label: "{parent.name}", value: parent.limitUse, format: pct } ] }
    open: "VAR NODE {book.id}"             # the command the primary button opens (/go?q=…)
    what: VaR limit                        # the WHAT term (docs/knowledge/glossary.yaml)
    knowledge: market-risk.md#4-a-worked-example-on-the-demo-data     # "Read more": a Knowledge page and anchor
```

| Step | Fetches or computes |
|---|---|
| `var: <path>`, `exposure: <path>` with `params` | `GET /api/v1/var<path>`, `/api/v1/exposure<path>` (VAR_GUIDE.md and EXPOSURE_GUIDE.md section 11), dated by the user's business date |
| `search: "<query>"` | a structured search (`HELD where PCT_OF_SHARES > 0 order by TOTAL_VALUE desc limit 1`): `{rows: [{ref, values: {"$.FIELD": …}}]}` |
| `raw: {kind, id}` | one record as stored: `{data: {…}}` |
| `each: <list>` on a fetch | once per element (`it` in its templates; at most `limit`, 100 by default, 500 at most): a list of `{it, ans}` |
| `let: <expression>` | a value |
| `pick: <list>`, `where`, `order` | the first row by `order` (largest first; `desc: false` for smallest) among those `where` keeps; none: the fact is not shown |

**Expressions** are Python's expression syntax cut down to reading data: names bound by earlier steps (and `asOf`), `a.b` and
`a['$.X']` (a missing key is `None`), `a[0]`, arithmetic, comparisons, `and or not`, `x if c else y`, lists and one-`for`
comprehensions, and the functions `len sum min max abs round first find pluck flat top ratio overlap common pairs index upper`. `None`
is forgiving: arithmetic with it is `None`, a comparison false, a division by zero `None`, so missing data hides the fact instead of
failing. Nothing else parses (no Python attributes, imports, lambdas, dictionaries, `**`). **Templates** fill `{expression}` or
`{expression|format}`: `money` (3.5m, 1,043.7bn), `int`, `num1`, `num2`, `pct`/`pct1`/`pct2` (a ratio: 2.23 is 223%),
`pctv`/`pctv1`/`pctv2` (a value already in percent), `signpct` (+452%), `times` (13.6×); a field that is empty hides the sentence.
**Pictures:** `bars` (2 to 6 bars, each `label`, `value`, `format`, `tone`: `base`, `alert` or `muted`), `share` (`value` of `of`,
drawn as a filled bar, marked at 100% when over; `label`, `format`) and `line` (`values`, optional `labels` and `mark`).

Check a file before you ship it: from `drishti-console/`,
`python3 -c "from pathlib import Path; from core.facts import check; print(check(Path('../config/packs')))"` prints each pack's
problems (an unknown key, a name no earlier step sets, a function or format that does not exist); `tests/test_home_facts.py` runs the
same check on every shipped pack, and fails when a sentence carries a figure of its own. A fact with a problem is left out and logged;
the others stand. Results are kept per server, user, business date and set of packs for `ui.facts.cache_seconds` (six hours); a fact
slower than `ui.facts.timeout_seconds` (90) is left out.

### Roles and field masks

A pack can add roles. A role says which kinds its holder may open and what else they may do. From the shipped packs:

```yaml
roles:
  trader:      { kinds: [trade, ir-curve, repo-curve, fx-spot, …, counterparty, book, …] }    # trading
  credit-risk: { kinds: ["*"], raw: true, calc: true }                                        # counterparty-risk
```

| Field | Meaning |
|---|---|
| `kinds` | the kinds the role may open; `"*"` means all. Links to other kinds show disabled, with the reason. |
| `raw` | sees every field: nothing in `drishti.security.redact` is masked for the role (otherwise those fields read `•••` in raw JSON, views, search, Impact and the type-ahead) |
| `author` | may use the Build workbench and propose Sutras |
| `approve` | may approve Sutra proposals |
| `admin` | may administer users |
| `calc` | may use Calc, Python in the browser on what the role opens ([USER_GUIDE.md](USER_GUIDE.md#9-roles-who-may-use-calc)) |

The core always has `viewer`, `author`, `approver` and `admin`. Roles are given to users by an administrator; see
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md). Security is off by default for local development; roles take effect when
it is on.

A pack's roles appear in *Admin → Roles* marked **built-in**, read-only: change them in the pack (or, for a
generated pack, in its generator). An administrator can also define new roles there without touching any pack;
the dialog's *Add every kind of a pack* buttons fill in a pack's kinds in one click
([USER_GUIDE.md](USER_GUIDE.md#admin--roles-what-a-role-may-do)).

#### Field masks: what a role without `raw` sees

What is hidden is **site** configuration, not a pack key: `drishti.security.redact` names fields, and a role without
`raw` sees every such field as `•••` on every path that shows or computes from its value (raw JSON, views, search,
Impact, the type-ahead, alert rules, Calc, the workbench's previews, BI datasets). A pack therefore decides *who is exempt*
(`raw: true` on its lead roles) and *which kinds a role may open*; the site decides *which fields are sensitive*.
Write your pack so that it works with any set of masked fields: a view that shows `•••` for a figure is correct, and
a test against sample documents always sees the unmasked values (tests run without roles).

How the setting works, and what it deliberately does not hide, is in
[API_GUIDE.md › Field masks](API_GUIDE.md#field-masks):

- **dotted paths**: a `redact` entry with dots names a field by the end of its path, so
  `lifecycle.timeline.description` masks every `description` under `lifecycle.timeline` and no other;
- **masks work on fields**, so a sensitive value copied into free text (a ticket's history line "Called J. Smith")
  stays readable unless the site turns on `drishti.security.mask-copies`, which scrubs exact copies of masked values
  from the other text of the same document. For the help-desk pack, a site that masks `CLIENT_NAME` should also turn it
  on, because `HISTORY` events may name the client;
- the setting list, with defaults, is in [CONFIGURATION.md](../admin/CONFIGURATION.md) (`drishti.security`).

Packs that carry personal or confidential fields should say so in their guide, so that an administrator knows what to
put in `redact`. The shipped setting masks the trader's identity on a trade (`TRADER_ID`, `TRADER_FIRST_NAME`, `TRADER_LAST_NAME`), the
counterparty's identifier (`counterpartyId`, which matches `COUNTERPARTY_ID`), and the `trader`, `patientName` and
`lifecycle.timeline.description` of the camelCase example packs, for exactly this reason. Names match in any case and convention (`counterpartyId`
masks `COUNTERPARTY_ID`); an entry that names no field of the loaded packs is logged at start-up as `DRS-6011`.

### Fragments and docstrings

A Sutra's panels need not be written out. The **fragment library** (2.7.0, [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#sutra-next-fragments-events-children-sources-layout-and-forms)) holds
generic, parameterised panels named after what they show. They are the **Drishti UI kit** (`config/ui-kit/fragments`,
`drishti.ui-kit.dir`): platform vocabulary the server and `sutra lint|test` always load, whatever packs are enabled, so every
Sutra may use them exactly like a shared block, and a pack needs to extend nothing for them:

```yaml
# config/packs/market-data/sutras/market-data/ir-curve.v1.sutra.yaml (the first screen built from fragments; now generated)
panels:
  - { use: curve-overview@1, with: { curve-code: CRV, curve-key: F2, table-key: F3 } }    # the zero-rate line, then the pillars
  - { use: series-line@1, with: { id: forwards, title: "1Y forward rates (%)", area: right, rows: $.FORWARDS, x: TENOR, y: RATE, fmt: price2 } }
  - { use: data-provenance@1 }
  - { use: linked-entities@1 }
```

The library today: `series-line`, `curve-table`, `curve-overview` (a composite of the two), `field-list`, `ranked-bars`,
`cashflow-ladder`, `event-timeline`, `key-figure`, `linked-entities`, `risk-links` (a trade's P&L vector, VaR and exposure, or why not), `data-provenance`, `explainer-note`, `measure-table`,
`region-map`, `source-footer` (a composite: data source, how the view was built, linked entities), `row-picker` (a table whose
rows, clicked, fire an event: the master of a master-detail screen; a fragment whose panels emit lists the events in `doc.emits`, one
whose panels read `@parent` says so in `doc.expects`; RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, sections 6 to 8 wire them). The world-economy economics
screens are worked examples. What each one shows, its
parameters with their types and defaults, and examples are in its docstring; read them all with
`curl -s localhost:8080/api/v1/ui-kit | jq '.fragments[] | {ref, summary}'` (`/api/v1/sutras/library` is the older alias). The
kit also holds icons, images, logos and the brand (`config/ui-kit/assets`, `brand.yaml`; [RACHANA_REFERENCE.md, The Drishti UI
kit](RACHANA_REFERENCE.md#3a-the-drishti-ui-kit)).

**Your pack's own fragments are namespaced.** A fragment or block in your pack's `sutras/` is `<pack>/<name>@N`
(`trading/deal-ticket@1`) and also answers to its plain name (`deal-ticket@1`) as before. Do not name one like a kit fragment: the
kit's wins the plain name, yours answers only qualified, and loading warns (`DRS-2048 warning: pack trading's key-figure has the
name of a Drishti UI kit fragment: the kit's wins; use trading/key-figure@1 for the pack's`).

**Writing a fragment.** A file `<name>.v<N>.fragment.yaml` (in the kit's `fragments/` for platform vocabulary, or in your pack's
`sutras/` for a pack's own) with
`fragment: <name>`, `version`, `params:` (each with its default; an empty default `''` leaves an optional key such as `code`, `key`
or `area` out), `panels:` whose text reads `%{param}`, and a complete **`doc:`**:

```yaml
rachana: 1
fragment: ranked-bars
version: 1
description: "Horizontal bars, one a row, labelled and sized by a value: the largest contributors at a glance."
params: { id: bars, title: By contribution, rows: $.ROWS, label: LABEL, value: VALUE, fmt: signed0, code: '', key: '', area: '' }
doc:
  summary: "Horizontal bars, one a row, labelled and sized by a value: the largest contributors at a glance."
  description: >-
    One horizontal bar a row of a list, labelled by one field and sized by another. Bars keep the order of the list.
  params:
    id:    { type: id, default: bars, meaning: "The panel id, unique on the screen" }
    title: { type: text, default: By contribution, meaning: "The title over the bars" }
    rows:  { type: expression, default: $.ROWS, meaning: "The list of rows, one bar each" }
    label: { type: field, default: LABEL, meaning: "The field of a row naming its bar" }
    value: { type: field, default: VALUE, meaning: "The field of a row giving its bar length" }
    fmt:   { type: format, default: signed0, meaning: "The named format of the value" }
    code:  { type: key, default: '', meaning: "The panel's mnemonic code; none when empty" }
    key:   { type: key, default: '', meaning: "The function key that brings the panel forward; none when empty" }
    area:  { type: area, default: '', meaning: "main (the default when empty) or right" }
  emits: []
  expects: []
  examples:
    - { use: ranked-bars@1, with: { id: by-book, title: "P&L by book", rows: $.BY_BOOK, label: BOOK, value: PNL, key: F2 } }
  since: "2.7.0"
panels:
  - id: %{id}
    kind: hbar
    title: "%{title}"
    code: %{code}
    key: %{key}
    area: %{area}
    rows: %{rows}
    label: %{label}
    value: %{value}
    fmt: %{fmt}
```

The docstring is checked strictly (`DRS-2055`; a fragment without a complete one is not loaded, so its uses fail with `DRS-2048`):
a one-line `summary` (at most 120 characters), a `description`, every parameter under `doc.params` with `type` (`text`,
`expression`, `field`, `format`, `key`, `area`, `id`, `number`, `boolean`, `kind`, `columns`), `default` (the same as under `params:`) and
`meaning`, and none it does not declare; `emits` and `expects` (`[]` until events and children arrive); at least one example that
is a use of this fragment with declared parameters (written with the plain name, `trade-table@1`, even for a pack's own); `since`.

**A list as a parameter (`columns`).** A parameter may be a list, typically a table's columns: declare it with a list default
(`columns: []`), document it as `type: columns`, and write `columns: %{columns}` in the panel. The Sutra passes the columns as a
table panel writes them, and they are filled in as one line of YAML flow, so labels and binds with quotes and commas keep exactly what
they say. The trading pack's derived pages use `trading/trade-table@1` this way:

```yaml
  - use: trading/trade-table@1
    with:
      title: "Trades (${size($.TRADES[?oneOf(@.TRADE_STATUS, ctl.status)])} of ${$.TRADE_COUNT})"
      code: TRD
      key: F2
      where: "oneOf(@.TRADE_STATUS, ctl.status)"
      columns:
        - { label: Trade, bind: "link(@.TRADE_ID, 'trade')" }
        - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
```

A text parameter is filled in as written, without the Sutra's quotes: written unquoted in the fragment (`where: %{where}`) an empty
one leaves the key out, but a value starting with `@`, a quote or a brace would not read, so quote such a parameter in the fragment
(`title: "%{title}"`), where an empty value is an empty text. The pack fragments that ship: trading's `deal-ticket`, `legs`,
`flow-schedule`, `events`, `payments`, `payments-metrics` and `trade-table` (generated, `tools/packgen/trades/sutras.py`);
news-and-markets' `quote-ranges`, `price-bars`, `headlines`, `headline-pipeline` and `snapshot-note` (`sutras/common/`, by hand).

**Composite fragments.** A fragment may use other fragments at a **pinned** version and pass parameters explicitly; it lists them
under `doc.uses`. At most three fragments deep, no cycles (`DRS-2048`):

```yaml
fragment: curve-overview
params: { rows: $.POINTS, curve-title: Zero rates (%), ... }
doc: { uses: [series-line@1, curve-table@1], ... }
panels:
  - use: series-line@1
    with: { title: "%{curve-title}", rows: "%{rows}", ... }
  - use: curve-table@1
    with: { rows: "%{rows}", ... }
```

**A Sutra's docstring.** A Sutra carries a `doc:` too (`summary`, `description`, `params` for its controls, `emits`, `expects`,
`examples` as URLs or commands that open it, `since`). Today it is a warning, shown on request: `sutra lint <pack> --docs` prints an incomplete one line by
line and counts the Sutras without one (`warning DRS-2055 19 of 20 Sutras have no doc: block`); an older `.block.yaml` without a
docstring is a warning too. The run still passes, with or without `--strict`. Write one whenever you touch a Sutra.

**Generated Sutras are fragment Sutras.** The pack generators write the Sutra Next form themselves (RACHANA_REFERENCE.md, open
question 4): `tools/packgen/banking/make_sutras.py` for core, market-data, market-risk and counterparty-risk (`FRAGMENT_PACKS`),
`tools/packgen/common/packbuild.py` for a pack whose spec sets `fragments=True` (world-economy's politics-and-society screens).
The writer (`tools/packgen/banking/kit_sutras.py`) turns a panel into a kit fragment use only when the fragment draws it exactly:

| The taxonomy's panel | Written as |
|---|---|
| a plain line (rows, x, y, format) | `series-line@1` |
| plain horizontal bars (rows, label, value, format, tone) | `ranked-bars@1` |
| a key/value list over an object (`rows:`, no columns) | `field-list@1` |
| a curve's pillars (Tenor, Instrument, Quote, Zero, DF) | `curve-table@1`; after the zero-rate line over the same rows, one `curve-overview@1` |
| how the view was built / linked entities | `data-provenance@1` / `linked-entities@1` |
| the data source and licence (`$.PROVENANCE`) last, then provenance and links | one `source-footer@1` |

Anything a fragment cannot say (a table's own columns, series, markers, pivots, controls that tune a panel) stays written in place.
Parameters equal to the fragment's default are left out. Every generated Sutra carries a generated `doc:`: the summary from the
kind's description (cut to 120 characters), a description listing its panels, strip and links, `params` for its controls, and two
examples opening the kind's first committed sample (`/v/equity/EQ-CSCA`, `EQ EQ-CSCA`); `DOCS` in `make_sutras.py` replaces a
generated docstring with a hand-written one (ir-curve). The trading pack's deal screens use the pack's own fragments
(`trading/deal-ticket@1`, `trading/legs@1`, `trading/flow-schedule@1`, `trading/events@1`, `trading/payments@1`, written by
`tools/packgen/trades/sutras.py` with complete docs) and the kit's footer. Lint it per pack (`sutra lint config/packs/trading`)
or the whole packs root (`sutra lint config/packs`): each pack folder (a `pack.yaml` beside a `sutras/` folder) loads its fragments
under its own namespace either way, so `trading/deal-ticket@1` resolves as it does on the server, and a pack may use another
pack's fragment by its qualified name when both are linted together. The root's `<pack>/tests` folders are skipped.

Each pack was moved only when its equivalence test (`PackSutraNextEquivalence` and one subclass per pack in drishti-server) drew
every screen identically, for a full and a masked reader, against the old inline layouts kept as test data
(`src/test/resources/sutra-next/<pack>/<name>.v2.sutra.yaml`). To move another generated pack: copy its current Sutras there as
version 2, switch the generator on, regenerate, and add the subclass.

**Changing a fragment.** Never change what a released version draws: write `name.v2.fragment.yaml` and move Sutras one at a time.
To prove a migration changes nothing, compare the view models before and after, as `SutraNextEquivalenceTest` does for ir-curve
(full and masked reader).

### Calc: Python snippets

Calc is a Python panel on any view (`Alt+C`): code that runs in the user's browser, reads the screen and Drishti with
the user's own rights, and draws tables and charts ([USER_GUIDE.md](USER_GUIDE.md#calc-python-on-any-view)). A pack decides whether its
kinds offer it, and may ship starter code:

```yaml
python:
  enabled: true                 # Calc on the views of this pack's kinds
  snippets:                     # optional starters, offered in the panel's Snippets list
    - title: Member trades, summed from the screen
      description: The netting set's member trades as a DataFrame, notional by product.
      kinds: [netting-set]      # where it is offered; none: every kind of this pack
      code: |
        trades = view.tables["Member trades"]
        trades.groupby("Product", as_index=False)["Notional"].sum()
  dir: python                   # optional: the folder of snippet files (default python/)
```

Longer snippets read better as files: `config/packs/<pack>/python/<name>.py`, in name order after the inline ones, each
starting with three comment lines (after the copyright header, which is not shown to the user). From
`config/packs/market-risk/python/stress_heatmap.py`:

```python
# title: Stress results: scenarios by desk, limits and the worst case
# description: Every stress result in one search, pivoted scenario by desk with totals; each desk's worst scenario and its use of the stress limit (results read in full for their limits), the scenarios ranked by firm-wide loss, and a heatmap of the matrix (matplotlib).
# kinds: stress-result, stress-scenario

import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
...
```

| Rule | |
|---|---|
| Where Calc is offered | on a view whose kind is owned by a pack with `python.enabled: true`, to users with a role with `calc` |
| Which snippets a view lists | from every active pack that enables Calc: those naming the view's kind in `kinds`, and those naming no kind from the pack that owns it |
| Limits | a file over 64 KB is skipped; at most 100 snippets a pack |
| When changes apply | when the pack reloads (`GET /api/v1/packs` carries them to the console) |
| Roles | give the pack's analyst roles `calc: true` ([Roles](#roles-and-field-masks)); a pack that enables Calc without such a role offers it only to roles that have it elsewhere |

The banking packs enable Calc with a library of 71 snippets (27 in `market-data`, 13 in `market-risk`, 9 in `trading`,
12 in `counterparty-risk`, 10 in `core`; `make_packs.py --check` prints `5 pack manifests up to date (71 Calc snippets)`): curves, FX, vol
surfaces, options, bonds, credit, VaR and its backtest, stress, FRTB, exposure, CVA, collateral, SA-CCR, desks, books and counterparties.
Their generator writes them from `tools/packgen/banking/snippets/<pack>/*.py` (read by `calc_snippets.py`). They share the pricing maths
of `drishti.quant`. Every one, with the sample entity it runs on, is in [USER_GUIDE.md › Snippet catalogue](USER_GUIDE.md#18-snippet-catalogue).

## Part III. Around the pack: inheritance, tests, versions, the registry

Around a pack once it is written.

### A signed pack registry: publishing and installing

Packs can be published to a **registry** and installed from Admin → Packs, with a version history and a rollback.
Every archive is signed; a server installs only what a publisher it trusts signed (ADR-018). `drishti.py pack keygen|publish|verify`
call `tools/packreg/packreg.py`, which you may also run directly.

**1. Make a signing key (once per publisher).** It needs `openssl`.

```bash
drishti-console/.venv/bin/python tools/drishti.py pack keygen --out ~/drishti-keys/acme
```

```text
private key: /home/you/drishti-keys/acme.pem (keep it secret; it signs packs)
public key:  MCowBQYDK2VwAyEAcYvYL5cL9D3XYx6w49WZ4oAnDrhjAfRT95Twju2ug+A=
servers trust it with:  drishti.packs.registry.trusted-keys.<publisher>: <public key>
```

**2. Publish a pack.** The registry is a folder (put it on a web server, or a shared drive):

```bash
drishti-console/.venv/bin/python tools/drishti.py pack publish config/packs/helpdesk --registry /srv/drishti-registry \
    --key ~/drishti-keys/acme.pem --publisher acme
```

```text
published helpdesk 0.1.0 (10424 bytes, sha256 1661eb4570b9…) signed by acme
```

The folder now has `helpdesk-0.1.0.zip` and `index.json`. Publishing the same folder again makes the same archive, byte for byte. Bump
`version:` in `pack.yaml` for each release; old versions stay in the registry.

**3. Trust the publisher on a server**, and point it at the registry:

```yaml
drishti:
  packs:
    registry:
      url: https://packs.bank.example/drishti/     # or a folder: /srv/drishti-registry
      trusted-keys:
        acme: MCowBQYDK2VwAyEAcYvYL5cL9D3XYx6w49WZ4oAnDrhjAfRT95Twju2ug+A=
```

**4. Install.** Admin → Packs shows **From the registry**: every pack version, its publisher (*trusted* or *not
trusted*), and what is installed and loaded here. **Install** (or `drishti.py pack install`) downloads the archive and checks its
SHA-256, its signature, that its files stay inside the pack, and that its `pack.yaml` names the same pack and version. Only then
is it unpacked into `<data>/packs/installed/<name>/` and loaded (it takes effect at once, with no restart). Installing another
version upgrades; the replaced version is kept and **Roll back** puts it back.

Installed packs are looked for before the shipped ones (`drishti.packs.dir`), so an installed `trading` 1.1.0
replaces the shipped `trading`. Check a registry from the command line:

```bash
drishti-console/.venv/bin/python tools/drishti.py pack verify --registry /srv/drishti-registry --publisher acme \
    --public-key MCowBQYDK2VwAyEAcYvYL5cL9D3XYx6w49WZ4oAnDrhjAfRT95Twju2ug+A=
```

```text
ok   helpdesk 0.1.0
```

| Refused because | Message (Admin → Packs) |
|---|---|
| the publisher's key is not in `trusted-keys` | `refused to install 'x': its publisher 'y' is not trusted on this server` |
| the archive changed after it was indexed | `… its SHA-256 is …, the index says …` |
| it was signed with another key | `… its signature does not verify with y's key` |
| a file would land outside the pack (`../`) | `… it has a file outside the pack: …` |
| `pack.yaml` names another pack or version | `… its pack.yaml says …, the index says …` |
| the registry is plain `http:` | `plain http registries are refused` |

### Loading a pack while the server runs

You do not need access to the machine to bring your pack in: an administrator presses **Load** in *Admin → Packs* for
a pack that is on disk but not named in `DRISHTI_PACKS` (or **Install** for one from [the registry](#a-signed-pack-registry-publishing-and-installing),
or deploys its archive); a pack folder copied into the packs folder is loaded by itself. The server runs the start-up check on the loaded
packs plus yours, records the pack in the overlay `<data>/packs/added.yaml`, and puts it to use **without a restart**; if the pack cannot be
used, the last good packs keep running. The steps, the API and the audit entries are in [Part V. Domain packs: the packs that ship, turning them on, who sees which](#loading-a-pack-while-the-server-runs-1).

What that means for the author: **the check is the one that refuses a bad pack at start-up** (a kind another pack owns, a
clash with an unrelated pack, a bad manifest), so a pack that loads on your scratch server loads on theirs. Test with
the packs they run: if their site runs `trading` and your pack defines `TRD` or `trade`, you will find out on the scratch server, not
in front of the administrator.

### Inheritance

`extends: [a, b]` means: this pack has everything `a` and `b` have — their kinds (to open), mnemonics, id patterns
and link fields, badges, roles, connectors and routes, Sutras and blocks, labels and formats, About vocabulary, samples, alert
suggestions, workspaces and help — and turning this pack on turns them on too.

When two **related** packs define the same thing differently, **the more specific definition wins**, for every user:

1. a child wins over its parents;
2. among parents, **the rightmost wins**: with `extends: [market-data, trading]`, `trading` beats `market-data`;
3. a pack reached through several parents counts once, below all of them.

This order is C3 linearisation, the rule Python uses to order classes (ADR-015).

#### Worked example: the order for `counterparty-risk`

The packs involved, as shipped:

```text
core:               extends []
market-data:        extends [core]
trading:            extends [core, market-data]
counterparty-risk:  extends [trading]
market-risk:        extends [market-data, trading]
```

Work it out from the bottom:

| Pack | Order (most specific first) | Why |
|---|---|---|
| `core` | core | no parents |
| `market-data` | market-data → core | one parent |
| `trading` | trading → market-data → core | rightmost parent (`market-data`) first, then `core`; `core` appears once, after `market-data` (which also extends it) |
| `counterparty-risk` | counterparty-risk → trading → market-data → core | one parent, `trading`, then its order |
| `market-risk` | market-risk → trading → market-data → core | rightmost parent (`trading`) first; `market-data` comes once, after `trading` (which also extends it) |

So if `counterparty-risk`, `trading` and `market-data` all defined a mnemonic `FIX`, the `counterparty-risk`
definition would be used. If only `trading` and `market-data` defined it, `trading`'s would win. On the shipped packs nothing is
overridden: *Admin → Health* lists `overrides: []`.

#### Worked example: overriding a parent

Say your bank wants `NSET` to read "Netting set (CSA)" and wants netting sets read from its own PostgreSQL
connector, without editing the shipped pack. Create `config/packs/my-bank/pack.yaml`:

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
    settings:                                                # table mode; see CONNECTORS.md for the jdbc settings
      url: ${BANK_CREDIT_JDBC_URL}                           # e.g. jdbc:postgresql://db:5432/credit
      user: ${BANK_CREDIT_DB_USER}
      password: ${BANK_CREDIT_DB_PASSWORD}
      table: credit.entities                                 # (kind, id, business_date, doc jsonb)
routes:
  netting-set: bank-credit-db                                # replaces counterparty-risk's route
```

When the pack loads the server writes `config/connectors/bank-credit-db.yaml` from the template (the environment variables are read when
the connector starts; a literal password would be refused). Start the server with `DRISHTI_PACKS=my-bank` (or copy the folder in):
`counterparty-risk`, `trading`, `market-data` and `core` come with it. *Admin → Health* lists every override, one per line, in the
form `<what> <name>: <winner> overrides <loser>`:

```text
mnemonic NSET: my-bank overrides counterparty-risk
route netting-set: my-bank overrides counterparty-risk
```

The same list is in `GET /api/v1/admin/health` under `overrides`. It is empty when nothing is overridden.

#### Worked example: a child of two parents (the rightmost wins)

This example is the one `PackLoaderTest.aChildInheritsItsParentsAndTheRightmostParentWins` builds and checks
on every build, so the results below are guaranteed. Four small packs:

```yaml
# config/packs/base/pack.yaml
pack: base
kinds: [curve]
mnemonics: { CRV: { kind: curve, label: Curve } }
roles: { viewer: { kinds: [curve] } }
graph: { badges: { curve: "'base'" } }
```

```yaml
# config/packs/left/pack.yaml
pack: left
extends: [base]
kinds: [trade]
mnemonics: { TRD: { kind: trade, label: Left trade } }
roles: { trader: { kinds: [trade] } }
graph: { badges: { curve: "'left'" } }
```

```yaml
# config/packs/right/pack.yaml
pack: right
extends: [base]
kinds: [quote]
mnemonics: { TRD: { kind: trade, label: Right trade } }
roles: { trader: { kinds: [trade, quote] } }
```

```yaml
# config/packs/child/pack.yaml
pack: child
extends: [left, right]
kinds: [var]
roles: { viewer: { kinds: [curve, trade, var] } }
```

Start the server with `DRISHTI_PACKS=child` (and no shipped pack, since these kinds are theirs). All four load, and the order, most
specific first, is:

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

#### What can be redefined

| Thing | A more specific pack may |
|---|---|
| mnemonic, link field, badge, role, connector, route | define the same name again; its definition replaces the parent's |
| Sutra | ship a Sutra with the same `name` and `version` (it replaces the parent's), or another name with a higher `match.priority` |
| block | ship a block with the same name and a higher version, and `use:` it |
| labels (`semantics.yaml`) and formats (`formats.yaml`) | define the same label or format; the more specific pack's wins |
| impact measures and formats | define the same kind; the more specific pack's wins |
| About entries | replace an entry by its key ([About: Inheritance](#inheritance-extends)) |

Two rules never bend:

- **A kind belongs to exactly one pack.** A child cannot redefine a parent's kind; it re-lays it out with a Sutra.
- **Unrelated packs may not disagree.** If neither pack inherits from the other, and no loaded pack inherits from
  both, the same name must be defined identically, or the server stops with:
  `mnemonic X is defined by both pack 'a' and pack 'b', which do not inherit from each other; declare it identically, or make one pack extend the other`.
  For example, `counterparty-risk` and `market-risk` both extend `trading` but not each other: they may not define
  the same role differently (`AllShippedPacksTest` loads every pair of shipped packs to prove they do not).

### Testing a pack

Four layers, from the cheapest. The first three need no server.

| Layer | Command | What it proves |
|---|---|---|
| Sutra lint | `sutra lint config/packs/<pack>` (or the whole `config/packs`) | every Sutra and block parses and its keys, expressions and panel options are valid (`DRS-…` codes with the line); help warnings |
| Sutra tests | `sutra test config/packs/<pack>` | each Sutra renders against the samples in `tests/<sutra>/` with no panel in error, the panels `expect.yaml` names are filled, and the help coverage |
| Both, for CI | `drishti.py pack check config/packs/<pack> [--strict] [--junit DIR]` | lint and test in one command, exit `0` / `1` / `2` |
| The pack's tests in the build | `./mvnw verify` | the manifest loads, every `config/packs/*/sutras/` validates, every Sutra survives imperfect data, `sutra test` runs over every pack that has tests, every shipped example loads |
| A scratch server | the [checklist](#verifying-a-pack) | links resolve, connectors are up, commands open, the guide renders |

`sutra lint` and `sutra test` are commands of the server's exec jar (`java -jar drishti-server/target/drishti-server-2.9.6-exec.jar sutra …`);
`pack check` finds the jar itself (`--jar`, `DRISHTI_JAR`). Both read the overlay of the checkout they run in (`<data>/packs/added.yaml`), so
run them where that overlay names only packs that exist (or set `DRISHTI_DATA_DIR` to an empty folder).

#### `tests/` and `expect.yaml`

```text
config/packs/<pack>/tests/<sutra>/        one folder per Sutra, named by the Sutra's name (the `sutra:` line)
    cases.jsonl                    the cases, one a line: {"case": "TKT-1001", "document": {...}} (the kind comes from the Sutra's match.kind)
    expect.yaml                    optional
```

```yaml
noErrors: true            # no panel in an error state on any sample (the default)
nonEmpty: [history]       # these panels must render with data on every sample
help: { coverage: 0.9, about: true }   # optional: the share of shown fields with a glossary entry, and that `about` renders
samples:                  # optional: more expectations for one sample file
  TKT-1002.json: { nonEmpty: [history] }
```

Any other key in `expect.yaml` is a usage error (exit `2`), so a typo cannot silently pass. Without a `tests/<sutra>/`
folder a Sutra is reported `skip (no samples)`, which is not a failure. Choose samples that differ: a ticket with one
history entry and one with many, a closed one, one with a missing field. A shipped pack also keeps `tests/help-coverage.txt`, a ratchet the
help coverage may not fall below. Exit codes: `0` ok, `1` problems, `2` usage. Every command and option (`--junit`, `--out` snapshots, the
shape and design commands) is in [SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md#15-testing-expectyaml-sutra-linttestpreview-ci).

`pack check` on a shipped pack (`--tail 2` keeps the last two lines of each step):

```text
$ drishti-console/.venv/bin/python tools/drishti.py pack check config/packs/market-risk --tail 2
== market-risk: sutra lint: exit 0
ok      config/packs/market-risk/sutras/market-risk/trade-valuation.v1.sutra.yaml
ok      config/packs/market-risk/sutras/market-risk/var.v1.sutra.yaml
== market-risk: sutra test: exit 0
     var: help coverage 5/5 (100%)
ok   config/packs/market-risk/sutras/market-risk/var.v1.sutra.yaml (2 samples)
```

#### In CI

```sh
drishti.py pack check config/packs/helpdesk --strict --junit build/reports          # lint + test, help warnings fail, JUnit XML
java -jar drishti-server-exec.jar sutra preview config/packs/helpdesk --out target/snapshots   # HTML snapshots to attach as build artifacts
```

Publish the JUnit files with your CI's report step. A Sutra whose panels read other entities with `source:` needs
those entities' packs enabled for the run: set `DRISHTI_PACKS=…` in the job, as for the server. In this repository
`SutraCliTest` runs `sutra test` over every shipped pack that has a `tests/` folder, so a Sutra change that breaks a
pack's own tests fails the Maven build. The Build workbench writes the same layout when you export a
[fragment](#pack-fragments-from-the-build-workbench), so an exported fragment passes `sutra test` unchanged.

#### The checks that run on every build

| Check | Where | What it proves | Picks up a new pack by itself? |
|---|---|---|---|
| `PackLoaderTest` | `drishti-packs` | the loader: manifests, inheritance order, overrides, refusals (clashes, cycles, missing packs) | — (tests the rules, with packs it writes itself) |
| `AllShippedPacksTest` | `drishti-packs` | the seven default packs and the opt-in public-data packs (us-equities, gleif) load together (no fixed count), any two of them load without a role or mnemonic declared differently, and every retired example pack still loads on top of them | yes: every folder of `config/packs` and `examples/packs` |
| `PackSutrasTest` | `drishti-rachana` | every pack's Sutras parse and validate with zero problems, and every `name@version` is unique | yes: every `config/packs/*/sutras/` |
| `SutraCliTest` | `drishti-server` | `sutra test` over every pack with a `tests/` folder | yes |
| `ImperfectDataTest` | `drishti-server` | every Sutra of every pack survives missing fields, wrong types and flipped shapes: the view still builds, and empty panels say *No data available* | yes |
| `DomainPacksTest` | `drishti-server` | the shipped packs load together, and every example command they advertise opens a full view built by a Sutra, every panel filled, no link missing (news-and-markets: by its Sutra only, its internet connectors being off) | the default pack list (`PackPaths.DEFAULT_ENABLED`) |
| `SutraCoverageTest` | `drishti-server` | every source system × product type (× sub type) of the trading data is laid out by exactly one Sutra | trading only |
| `BankingPacksTest` | `drishti-server` | enabling two risk packs brings the packs they extend, their Sutras and their data-domain connectors | no |
| `ShippedExamplesPacksTest` | `drishti-server` | every BI item shipped in `config/bi` reads only what the seven packs serve (kinds, connectors, `pack.<pack>.<kind>` datasets) | yes |
| `ExamplePreviewTest`, `ConnectorExamplesTest` | `drishti-server` | every documented Sutra example previews without a panel error; every example connector file is valid and holds no secret | yes |
| generator checks | `tools/packgen/*/make*.py --check` | generated files are exactly what the generator writes | yes, for generated packs |
| `LicenseHeaderTest` | `drishti-it` | every file carries the copyright header | yes |

Run the pack-related tests alone while you work:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q -pl drishti-packs -am test -Dtest='PackLoaderTest,AllShippedPacksTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl drishti-rachana -am test -Dtest=PackSutrasTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -q -pl drishti-server -am test -Dtest='ImperfectDataTest,DomainPacksTest,SutraCliTest' -Dsurefire.failIfNoSpecifiedTests=false
```

A pack of your own outside this repository gets the same guarantees from `pack check` in its CI and the [checklist](#verifying-a-pack)
on a scratch server; a pack added to this repository's default list (`PackPaths.DEFAULT_ENABLED`) is tested by `DomainPacksTest` and
`AllShippedPacksTest` like the shipped ones.

### Versioning a pack

**The pack's `version`** (`version: 1.2.0`, **X.Y.Z**) is a label: it is shown on About, *Admin → Health* and *Admin → Packs*, and the
registry's index, the deploy check and the install checks use it, but nothing in the pack depends on it. Raise it whenever the pack changes, so an
administrator can tell which copy a server runs and the registry can offer the new one:

```bash
curl -s $B/packs | python3 -c 'import json,sys; [print(p["name"], p["version"]) for p in json.load(sys.stdin)]'
```

**Sutras carry their own versions** (`version: 2` inside the file, and `ticket.v2.sutra.yaml` as the file name by
convention). The highest version of each Sutra name is the one used. Keep the old file while views may still need it to
compare, or delete it; a name and version defined twice is an error (`DRS-2028`). Publishing a pack to the registry
makes the same archive byte for byte for the same folder, so bump `version:` before each `publish`. `drishti.py pack diff OLD NEW` says
what changed between two versions (folders or bundles) and marks removed or renamed kinds and mnemonics as **BREAKING**.

**What a change needs**, by part of the pack:

| You changed | It takes effect |
|---|---|
| a Sutra or block (`sutras/`) | within about a second (hot reload), on every server reading that folder |
| `pack.yaml` (any key), `config/formats.yaml`, `config/semantics.yaml`, `config/about.yaml` | within a few seconds (`drishti.packs.poll`): roles, graph, search columns and pivots, forms, formats, hints, About pages, routes are all swapped in live; a bad edit keeps the last good version. A connector template change does not touch an existing connector file ([reset it](#connectors-the-pack-names-them-the-site-owns-them)) |
| `samples/` | within a few seconds (the demo source is restarted on its new folders; nothing else is touched) |
| `console:` keys, `guides/`, `config/help.yaml`, `config/workspaces.yaml` | in the console within a minute |
| data behind a connector | at once, or at the connector's next rescan; a Delta date rewritten under a running server is read again by BI too |

Upgrading a pack on a running installation, and removing one, are administrator tasks: see
[Part V. Domain packs: the packs that ship, turning them on, who sees which](#upgrading-and-removing-a-pack). **Before you rename a kind, a mnemonic, an attribute or a link field**, remember
that saved monitors, workspaces, alert rules and BI items refer to kinds, fields and ids: a rename leaves them pointing at nothing, so
add the new name and keep the old one for a release where you can.

### Pack fragments from the Build workbench

You do not have to write a Sutra by hand. Design it in the Build workbench (from JSON samples, a JSON Schema, or an
existing Sutra: [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench)), and **ship** it as a pack fragment: *Ship → Export as a pack
fragment* downloads a zip you can put in `config/packs/`. The workbench section that describes the export, the import and the
file layout is [RACHANA_REFERENCE.md §24](RACHANA_REFERENCE.md#24-pack-fragments-export-and-import); this is what it means for
a pack author, from an export of the help-desk ticket Sutra (the design id is in the address bar):

![The Ship menu of the workbench: export as a pack fragment, import a pack, read-only link](img/packs/06-workbench-export.jpg)

```text
ticket-view/pack.yaml
ticket-view/sutras/studio/ticket.v1.sutra.yaml
ticket-view/tests/ticket/cases.jsonl
ticket-view/tests/ticket/expect.yaml
ticket-view/samples/ticket.jsonl
ticket-view/samples/catalog.jsonl
ticket-view/README.md
```

A design that has About text (the workbench's About tab) also writes `ticket-view/config/about.yaml` and an `about: config/about.yaml` line in the `pack.yaml`.

The generated `pack.yaml` is a **stub** (the kind, a mnemonic suggestion, an id pattern from the id field of your samples; abridged here:
its comments, the description and `extends: []` are left out), and `expect.yaml` records what `sutra test` found on your samples:

```yaml
pack: ticket-view
version: 0.1.0
code: TICK
title: "Ticket view"
kinds:
- ticket
sutras: sutras
mnemonics:
  TICK:
    kind: ticket
    label: "ticket"
graph:
  id-patterns:
  - pattern: "^TICK-"
    kind: ticket
```

```yaml
noErrors: true
nonEmpty:
- "history"
- "built"
- "refs"
```

From a fragment to a pack, as an author:

1. **Rename it.** The folder name, `pack:` and the title; set the real `version` and `code`.
2. **Fix the stub.** The mnemonic should be the one your users type (`TKT`, not `TICK`), the id pattern should match your real
   ids (`^TKT-`, not `^TICK-`), and the stub knows nothing of links, columns, roles or connectors: add them as in the steps above.
   `kinds:` must be a kind **no other pack owns**.
3. **Keep the tests.** `tests/<sutra>/` already passes `sutra test`; add a sample for each shape your data really has.
4. **Load it** from *Admin → Packs → Load* (the folder under `config/packs/`), or list it in `DRISHTI_PACKS`.

The other way round: *New screen → Or import a pack* takes a zip or a folder (any pack) and turns each `*.sutra.yaml`
into a design, with the pack's `tests/` and `samples/` documents (`samples/<kind>.jsonl` or the older `samples/<kind>/`) as its samples. Use
it to revise a shipped pack's Sutra in the workbench, then bring the new version back as a fragment (or propose it, for the live registry:
the workbench's *Ship → Propose* path puts it through review and approval). Stored-entity samples are references, not data, so they are
not exported; export from synthetic or uploaded samples. The examples of [docs/guides/examples](examples/README.md) each open as a design
in the workbench, which is the quickest way to see a fragment of each panel family.

### Verifying a pack

After any change, one minute on a scratch server tells you the pack is sound.

**In the console**

| Where | Look for |
|---|---|
| *Admin → Packs* (`/admin/packs`) | the pack, its version, **on**; *Needed by* shows what builds on it; its own page shows its kinds, Sutras, Sutra coverage and connectors |
| *Admin → Health* (`/admin/health`) | the pack **OK**, with its kinds, Sutras and connectors; **no Sutra problems**, no connector down or not configured, and the *Overrides* you expect and no others |
| `HELP` (the pack's `code`) typed on the command line | the pack's overview: every kind you may open, its mnemonic, how many entities the sources hold, an example, the key fields |
| *Help → Help centre* | the pack's guide card |

**On the command line**

```bash
B=http://localhost:18996/api/v1
curl -s $B/sutras/problems                                  # {}
curl -s $B/admin/health | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["overrides"]); print([(p["name"], p["status"], p["version"]) for p in d["packs"] if p["name"]=="helpdesk"])'
curl -s $B/packs/HELP/overview | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["name"], d["code"], [(k["mnemonic"], k["count"]) for k in d["kinds"]])'
curl -s $B/sources | python3 -c 'import json,sys; [print(s["name"], s["kinds"], s["health"]) for s in json.load(sys.stdin)["sources"] if s["name"] in ("helpdesk-store", "agent-totals")]'
drishti-console/.venv/bin/python tools/drishti.py pack check config/packs/helpdesk --tail 1
```

The output for the finished help-desk pack, with the file connector of step 16 on:

```text
{}
[]
[('helpdesk', 'OK', '0.1.0')]
helpdesk HELP [('TKT', 4), ('AGT', 1), ('CLI', 2), ('LOAD', 1)]
agent-totals ['agent-load'] UP
helpdesk-store ['ticket'] UP
== helpdesk: sutra lint: exit 0
ok      config/packs/helpdesk/sutras/ticket.v1.sutra.yaml
== helpdesk: sutra test: exit 0
ok   config/packs/helpdesk/sutras/ticket.v1.sutra.yaml (2 samples)
```

`[]` is the list of overrides (nothing of another pack was redefined). The four counts are the entities each kind has
for the business date: three sample tickets and the exported one.

**The checklist**

1. `sutras/problems` prints `{}`, and `pack check` passes (`--strict` too, if you keep every shown field explained).
2. *Admin → Health* shows the pack **OK**, with the overrides you expect (and no others), and no connector *down* or *not configured* (`connectorsNotConfigured`); `ls config/connectors` has a file for each connector the pack names.
3. Each `console.examples` command opens, and its *How this view was built* names a Sutra (or says `inference only` where
   you meant it to).
4. `<MN> <GO>` for each mnemonic gives a pick list with sensible columns; the type-ahead offers the mnemonics; `FORM <code>` opens a usable form.
5. The links of one entity of each kind resolve, and F8 on a central entity lists what depends on it.
6. A role without `raw` sees `•••` for the fields the site masks, and the views still make sense.
7. The guide renders in the help centre and its links open; `?` on a view shows your About sentence.
8. The pack's **version** is bumped, and the registry archive (`pack verify --registry`) is current.

#### Version rule and the wizard

`version:` in `pack.yaml` is **X.Y.Z** (optionally `-pre-release` and `+build`, such as `2.0.0-rc.1`). `pack bundle`, `pack make --version`, Build → New pack and the server's
deploy check refuse anything else with that sentence. Build → New pack keeps your work: each step is a history entry (the browser's Back moves between steps), a reload
restores the sources and choices from the tab's storage, and leaving with unsaved work asks first. For your own JSON Lines files the default source is the **File
connector** (its generated template tells you to set `root`).

## Part IV. Generated packs, the README standard, JSON Lines

### How the shipped packs are generated

The shipped packs are **generated**: a Python program writes their `pack.yaml`, Sutras, samples, guides and
help cards from one description. Each generated file says so in a comment near the top:

```yaml
# Generated by tools/packgen/banking/make_packs.py from the taxonomy. Edit the generator, not this file.
```

| Packs | Generator |
|---|---|
| `core`, `market-data`, `trading`, `market-risk`, `counterparty-risk` | `tools/packgen/banking/`: `make_packs.py` the manifests and the Calc snippets (`python/*.py`, from `snippets/<pack>/*.py` through `calc_snippets.py`), `make_sutras.py` the 180 Sutras and 6 blocks (the trading pack's from `tools/packgen/trades/sutras.py`), `make_docs.py` the guides, `make_data.py` the documents |
| the trades (`trading`'s samples and tests, and `data/testdata/trades/`) | `tools/packgen/trades/`: `schema.py` the common schema, `endur.py` the ENDUR deals, `convert.py` the CALYPSO and MUREX deals from the banking generator's product range, `amend.py` the daily amendments, `make_trades.py` the files and tables |
| `world-economy` | `tools/packgen/world/make.py`, joining the halves `tools/packgen/economics/make.py` and `tools/packgen/politics/make.py` (real public data through `tools/realdata/`), on the common builder `tools/packgen/common/packbuild.py` |
| the test data (`data/testdata/`) | `tools/testdata/make_testdata.py` (deterministic; `--check` says whether the files are current) |
| retired: `liquidity-risk`, `climate-risk`, `operational-risk`, `retail-banking`, `genomics` (in `examples/packs/`) | `tools/packgen/<area>/make.py` (`liquidity`, `climate`, `oprisk`, `retail`, `genomics`), on the same builder with `retired=True`, which writes under `examples/packs/` |
| `news-and-markets`; retired: `finance`, `logistics` | hand-written; the samples come from the pack's `tools/` folder (`config/packs/news-and-markets/tools/make_samples.py`, `examples/packs/finance/tools/`, `examples/packs/logistics/tools/`) |

**Attribute names.** Every document, Sutra, form, column list and BI item of the seven shipped packs names attributes in
UPPER_SNAKE (`COUNTERPARTY_ID`, `NET_MTM`, `PEAK_EE`, `ZERO_RATE`, `GDP_USD_BN`), as production systems write them. The
banking and world-economy generators build their documents with short internal names and turn them into the written
names at one boundary, `tools/packgen/common/upper.py` (`name`, `record` for documents and their `_meta.walk`, `kind`
for a taxonomy kind's Sutra paths, columns and links); a new field in a generator is written in UPPER_SNAKE without more
work. The trades generator writes the common schema directly. The retired example packs keep their own names
(`make_data.build(upper=False)`, `taxonomy.graph_fields(raw=True)`). The connectors that build their own documents (news, yfinance,
the public feeds) write UPPER_SNAKE too (`com.ash.drishti.common.UpperSnake`).

#### Never edit a generated file by hand

A hand edit to a generated `pack.yaml`, Sutra, sample or guide is lost the next time the generator runs, and
before that it **fails the drill**. `tools/drill.sh`, which must pass before anything is merged to `main`,
runs every generator in check mode:

```bash
python3 tools/packgen/banking/make_packs.py --check
python3 tools/packgen/banking/make_sutras.py --check
```

When everything is in step you see (run on 2.3.0):

```text
5 pack manifests up to date (71 Calc snippets)
186 Sutras up to date
```

After a hand edit to `config/packs/trading/pack.yaml`, the first command stops with
`pack manifests out of date; run make_packs.py: ['config/packs/trading/pack.yaml']`. The packbuild generators also
refuse files they did not write: an extra Sutra dropped into a generated pack's `sutras/` gives
`<pack> pack out of date; run tools/packgen/<area>/make.py. stale=[] extra=[…]`.

**Sections the generator does not write.** A top-level section of a banking pack's manifest that the taxonomy does not say (the
`history:` sections of market-data, market-risk, counterparty-risk and trading, the trading pack's `ingest:`) is kept in
`tools/packgen/banking/kept/<pack>.yaml` and appended to `pack.yaml` as written, comments and all. Edit it there; a kept section
the generator also writes is refused (`make_packs.py` names it).

#### Changing a generated pack, step by step

Example: show the trader in trade pick lists.

1. Find the generator named in the file's comment: `tools/packgen/banking/make_packs.py`.
2. Change its input. Here, the trade columns of the `COLUMNS` table near the top: add `"TRADER_ID"` after `"COUNTERPARTY"`.
3. Run the generator without `--check`. It rewrites the files:

   ```bash
   python3 tools/packgen/banking/make_packs.py
   ```

4. Check the result: `git diff config/packs/trading/pack.yaml` shows `- TRADER_ID` added under `columns: trade:`.
5. Wait for the server to reload the pack (a few seconds; no restart), type `TRD MX-200000` and press Enter: the pick list has a
   *Trader ID* column (masked, `•••`, for a role without `raw`: the shipped setting masks `TRADER_ID`).
6. Commit the generator **and** the files it wrote, together. `--check` now passes.

#### Changing a pack you do not own

To change a shipped pack for your site without touching its files at all, use one of these instead:

| You want | Do this |
|---|---|
| A different layout for one kind | Put a Sutra with a higher `version` in the site Sutra folder (`./sutras`, `DRISHTI_SUTRAS`), or design it in the Build workbench ([RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#screen-designer-and-the-build-workbench)) |
| Other mnemonics, labels, columns, connectors or routes | A small pack of your own that `extends` the shipped one ([Inheritance](#inheritance)) |
| A connector pointed elsewhere, or switched off | Its connector file, `config/connectors/<name>.yaml` (edit it, or use Admin → Connectors or `drishti.py connector apply`): a file replaces the pack's template and is applied without a restart ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)) |
| A pack hidden from everyone | *Admin → Packs* → **Switch off** ([Part V. Domain packs: the packs that ship, turning them on, who sees which](#switching-packs-off-and-on-admin--packs)) |

The shipped packs' lakes are built from the committed test data with one command ([Part V. Domain packs: the packs that ship, turning them on, who sees which](#the-demo-lakes-where-the-seven-packs-read-their-data)):

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

#### Writing your own generator

A pack with many kinds and many consistent documents is better **generated**: you describe kinds and documents in a
short Python script, and the common pack builder checks them and writes the manifest, Sutras, samples, guide, help
card and (optionally) a Delta Lake. The world-economy pack and the retired example packs are made this way
(`tools/packgen/common/packbuild.py`). This walk-through generates a small library pack with two kinds (book titles
and authors); the script below was run as written (into a scratch folder, `DRISHTI_PACKS_OUT=…`) and printed the lines shown. For a
handful of kinds, writing the pack by hand ([above](#build-it-step-by-step)) is simpler, and for existing data `pack new`
([below](#generating-a-pack-from-json-lines)) is quicker.

##### What you need

- the repository checked out, and Python 3 with `pyyaml` (the console's virtual environment has it);
- the server and console running (see [GETTING_STARTED.md](GETTING_STARTED.md)), so you can open the result.

##### Generator step 1. Create the generator script

Create `tools/packgen/library/make.py`. Start with the imports every generator uses:

```python
# tools/packgen/library/make.py: the top of the file
"""The library pack: book titles and authors.

    python3 tools/packgen/library/make.py            write config/packs/library
    python3 tools/packgen/library/make.py --check    fail if config/packs/library differs from what would be written
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "common"))
sys.path.insert(0, str(HERE.parent / "banking"))
import packbuild as PB  # noqa: E402  the common pack builder
import taxonomy as T  # noqa: E402  the banking packs' link fields, so yours cannot clash with them
from risk_model import Kind, Panel as P  # noqa: E402
```

(Every file in the repository also starts with the project's copyright header; copy it from any other
`make.py`, or run `python3 tools/license_headers.py --fix` afterwards.)

##### Generator step 2. Describe the kinds

A `Kind` names an entity type and describes its screen. Its arguments, in order:

| Argument | Example | Meaning |
|---|---|---|
| kind | `"book-title"` | The kind's name: lower-case, unique across all enabled packs (`book` is already a `core` kind). |
| mnemonic | `"TITLE"` | What users type: `TITLE TITLE-0001`. |
| prefix | `"TITLE-"` | Every id starts with it; it becomes the id pattern, so a bare `TITLE-0001` also opens. |
| label | `"Book"` | The human name. |
| group | `"Library"` | Groups the Sutras (`sutras/library/`). |
| desc | `"A title in the catalogue…"` | One sentence, used in the Sutra and the guide. |
| id_field | `"TITLE_ID"` | The document field that holds the id. |
| strip | list of `(label, path, format, tone, emphasis)` | The header figures, at most eight. |
| panels | list of `Panel` | The body. |
| `links=` | `{"AUTHOR": ("author", "Author")}` | Document field → (kind it points at, label). Makes the field a link. |
| `badge=` | `"$.ON_LOAN + ' on loan'"` | The short text shown beside a link to this kind. |

A `Panel` is `P(kind, id, title, rows, columns, …)` with `kind` one of `kv`, `table`, `line`, `area`, `hbar`,
`ladder` or `surface`; columns are `(label, path, format, tone, total)`; and `x=`, `y=`, `label=`, `value=`,
`fmt=`, `key=`, `area=` as in a Sutra. Paths are Rachana-EL, exactly as in a Sutra.

Add this to the script:

```python
# the two kinds
G = "Library"
KINDS = [
    Kind("book-title", "TITLE", "TITLE-", "Book", G, "A title in the catalogue: author, copies and loans.", "TITLE_ID",
         [("Title", "$.TITLE", None, None, True), ("Author", "$.AUTHOR_NAME", None, None, False),
          ("Copies", "$.COPIES", "amount0", None, False), ("On loan", "$.ON_LOAN", "amount0", None, False)],
         [P("line", "loans", "Loans per month", "$.LOANS_BY_MONTH", x="MONTH", y="LOANS", fmt="amount0", key="F2"),
          P("table", "copies", "Copies", "$.COPY_LIST", [("Barcode", "@.BARCODE", None, None, False),
            ("Branch", "@.BRANCH", None, None, False), ("Status", "@.STATUS", None, "status", False)], key="F3")],
         links={"AUTHOR": ("author", "Author")}, badge="$.ON_LOAN + ' on loan'"),
    Kind("author", "AUTH", "AUTH-", "Author", G, "An author and their titles.", "AUTHOR_ID",
         [("Name", "$.NAME", None, None, True), ("Titles", "$.TITLE_COUNT", "amount0", None, False)],
         [P("table", "titles", "Titles", "$.TITLES", [("Title", "@.TITLE", None, None, False)], key="F2")]),
]
```

Every generated Sutra ends with *How this view was built* and binds **F8** (impact) and **F9** (raw JSON). A kind
with `links` also gets a *Linked entities* panel and **F7** to its first link; a kind with no links gets neither.

##### Generator step 3. Write the documents

One function returns every document, by kind and then by id. Two rules:

- **Consistent:** every link must point at a document that exists (an author's `TITLE_COUNT` should match the titles
  that name them).
- **Deterministic:** the same input gives the same output (seed any random generator from the id), so `--check`
  can tell whether the committed pack is current.

```python
# the sample documents
def build() -> dict[str, dict[str, dict]]:
    meta = {"source": "catalogue", "generation": 1}
    docs = {"book-title": {}, "author": {}}
    docs["author"]["AUTH-001"] = {
        "AUTHOR_ID": "AUTH-001", "NAME": "Ada Okafor", "TITLE_COUNT": 1,
        "TITLES": [{"TITLE": "Rivers of Light"}], "_meta": meta}
    docs["book-title"]["TITLE-0001"] = {
        "TITLE_ID": "TITLE-0001", "TITLE": "Rivers of Light", "AUTHOR": "AUTH-001", "AUTHOR_NAME": "Ada Okafor",
        "COPIES": 4, "ON_LOAN": 3,
        "LOANS_BY_MONTH": [{"MONTH": "2026-07", "LOANS": 9}, {"MONTH": "2026-08", "LOANS": 11}, {"MONTH": "2026-09", "LOANS": 14}],
        "COPY_LIST": [{"BARCODE": "31000451", "BRANCH": "Central", "STATUS": "On loan"},
                      {"BARCODE": "31000452", "BRANCH": "Central", "STATUS": "Available"}],
        "_meta": {**meta, "live": True, "walk": {"ON_LOAN": 1}}}
    return docs
```

`_meta` says where a document came from. `"live": true` with `"walk": {"ON_LOAN": 1}` makes the demo source tick
that field up and down, so the view shows live updates.

##### Generator step 4. Describe the pack and call the builder

```python
# the pack itself
def spec() -> PB.PackSpec:
    return PB.PackSpec(
        name="library", title="Library", description="Titles, authors and loans.",
        requires=[],                                    # packs this one extends; written as extends: in pack.yaml
        generator="tools/packgen/library/make.py",
        domains={"library": KINDS},                     # data domain -> kinds; one Delta Lake connector each
        examples=[("TITLE TITLE-0001", "A book · loans and copies"), ("AUTH AUTH-001", "An author · titles")],
        roles={"librarian": {"kinds": [k.kind for k in KINDS], "raw": True}})


if __name__ == "__main__":
    PB.main(spec(), build(), T.graph_fields())
```

| `PackSpec` field | Meaning |
|---|---|
| `name`, `title`, `description` | The pack's id (its folder name and what `DRISHTI_PACKS` lists), display name and summary. |
| `requires` | Packs this one builds on, e.g. `["core"]` to link to counterparties. |
| `domains` | Data domain → kinds. Each domain becomes a Delta Lake connector `<domain>-store` reading `<root>/<domain>/<kind>/`. |
| `examples` | Commands shown on the landing page and in the pack's guide. |
| `roles` | Roles the pack adds: which kinds they may open, and whether they see raw JSON. |
| `code=`, `sample=` | The pack's code; a demonstration pack. |
| `columns=`, `layouts=`, `form_overrides=` | Pick-list columns, the Delta columns per kind, form field types and labels. |
| `impact=` | Optional F8 configuration (`follow`, `measures`, `formats`); see [F8 · Impact](USER_GUIDE.md#f8--impact). |
| `external_ids=` | Ids that live in a required pack, so links to them pass the check. |
| `overview=` | Extra prose for the generated guide. |
| `retired=` | Write under `examples/packs/` instead of `config/packs/`. |

##### Generator step 5. Run it

```bash
# write config/packs/library
python3 tools/packgen/library/make.py
```

You see:

```text
library: wrote 8 files (2 documents, 2 kinds)
```

and `--check` then prints `library: 8 files up to date`. Before writing anything the builder checks that every id field matches its key,
every link resolves (to this pack or, through `external_ids`, to a pack it requires), every path a strip or panel's rows reads exists in every
document, and no link field already means another kind in the banking packs. A failure stops it and names the document; with `AUTHOR`
pointing at `AUTH-002`:

```text
library data is inconsistent:
  book-title/TITLE-0001: AUTHOR -> author/AUTH-002 does not exist
```

Other commands:

```bash
# fail if config/packs/library differs from what the script would write (tools/drill.sh runs this for every generator)
python3 tools/packgen/library/make.py --check

# write somewhere else (a scratch folder, a review): the pack goes to $DRISHTI_PACKS_OUT/library
DRISHTI_PACKS_OUT=/tmp/review python3 tools/packgen/library/make.py

# also build ten business days of history in the Delta Lake, for the date picker
drishti-console/.venv/bin/python tools/packgen/library/make.py --lake data/delta
```

##### Generator step 6. What you get

```text
config/packs/library/
  pack.yaml                            kinds, mnemonics, id patterns, link fields, badges, roles, connector, routes, examples
  sutras/library/book-title.v1.sutra.yaml   one YAML Sutra per kind (rachana: 1, with a description)
  sutras/library/author.v1.sutra.yaml
  samples/book-title.jsonl             the documents, one a line, for the built-in demo source
  samples/author.jsonl
  samples/catalog.jsonl                 the list the demo source and suggestions read
  guides/library.md                    an overview guide: kinds, fields, links and "Try it" commands
  config/help.yaml                     the guide's card under Help → Domain packs
```

The `pack.yaml` it wrote names the link field `AUTHOR`, the badge `$.ON_LOAN + ' on loan'` and one connector template,
`library-store` (`plugin: delta`, `domain: library`, `root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}`), with
`book-title` and `author` routed to it. Never edit these files by hand: change the script and run it again (each file says so at the top).

##### Generator step 7. Open it

Copy (or write) the folder into the packs folder of a running server, and it is loaded; or start a server with it:

```bash
# start the server with only the library pack
DRISHTI_PACKS=library java -jar drishti-server/target/drishti-server-*-exec.jar
```

or alongside others: `DRISHTI_PACKS=market-risk,library`. The console reads the guide from the server, so it needs no restart. Then:

1. In the terminal, type `TI`. You see `TITLE` (Book) in the suggestions.
2. Type `TITLE TITLE-0001` and press Enter. You see the pill *Library · Book* and the id *TITLE-0001*; the
   strip *Title Rivers of Light* (highlighted), *Author Ada Okafor*, *Copies 4*, *On loan 3*; the panels *Loans
   per month* (F2) and *Copies* (F3); and, on the right, *Linked entities* with *Author AUTH-001*. *On loan*
   ticks.
3. Press **F7**: the author opens.
4. Open **Help → Domain packs**: the *Library pack* guide is there.

If an admin assigns packs per user, add `library` to your user under **Admin → Users** (see
[Users and roles](../admin/USER_MANAGEMENT.md)).

> **Live data and history.** Live views work from the samples straight away. Real history for the date picker needs the Delta Lake built
> with `--lake`; without it, a picked date shows the samples as they are, with a note that the source is not a
> dated source.

##### Generator step 8. Make it part of the build

- Commit `tools/packgen/library/make.py` and `config/packs/library/`.
- Add `library` to the default pack list (`PackPaths.DEFAULT_ENABLED`) if it should ship, so `DomainPacksTest` checks that every example
  command opens with no empty panel and no missing link, and `AllShippedPacksTest` that it loads with the others.
- `tools/drill.sh` runs every generator with `--check`, so a pack that drifts from its script fails the build.

### The README standard

Every pack has a `README.md` in its folder (`config/packs/<pack>/README.md`): one or two pages that say what the pack holds, for people,
for the console (Admin → Packs → the pack, *README: what the pack holds*) and for AI (`GET /api/v1/packs/{pack}/readme`, [API_GUIDE.md](API_GUIDE.md)).
It is **generated**, never edited: `tools/packgen/common/readme.py` reads `pack.yaml`, the samples, the Sutras and `config/about.yaml`,
and adds a short hand-written **intro**. `tools/test_readmes.py` fails when a pack (any folder under `config/packs`) has no README, a
missing or invalid front matter, an empty section, a kind missing from *Data structures*, or a README its generator would write
differently.

**The shape.** The licence comment, a YAML front matter, then seven `## ` sections in this order:

```markdown
---
pack: library                 # the folder's name
version: 1.0.0
code: LIBS
title: Library
summary: One line.
kinds: [book-copy, loan]
commands: {BKC: book-copy, LOAN: loan, LIBS: pack overview}
depends-on: {packs: [core], connectors: [library-store], plugins: [delta], settings: [DRISHTI_LAKE_ENABLED]}
sources:
- {name: The library's catalogue, licence: Proprietary, internal-only: false, used-for: book-copy}
refresh: tools/publicdata/library.py      # or the generator, when the data has no refresh script
generated-by: tools/packgen/library/make.py
---
# The library pack
## What it is
## Data structures            # per kind: ### `kind`, its id rule, a field table (Field | Type | Example | Description),
                              # nested structures by path (LEGS[].CASH_FLOWS[]) and the links to other kinds
## Screens and commands
## Data sources and licences
## Enabling and dependencies
## Refreshing the data
## See also
```

**Where the words come from.** Types and examples from the samples (up to 4,000 documents a kind; a field present in only part of
them says so), ids from the catalogue and `graph.id-patterns`, links from `graph.fields` (the pack's and those of the packs it
extends), descriptions from `config/about.yaml` glossaries, then the Sutras' labels, then the intro's `fields:`. A kind without samples
(read live, or derived) lists the fields its screens show.

**The intro**, `tools/packgen/<generator>/readme/<pack>.intro.md` (or `tools/packgen/intros/<pack>.intro.md` for a pack without a
generator), holds what cannot be read from the files:

```markdown
<!-- licence comment -->
---
generator: tools/packgen/library/make.py
sources:
- name: The library's catalogue
  licence: Proprietary
  internal-only: false
  used-for: book-copy, loan
fields:
  loan:
    _: One loan of one copy to one member.          # a line under the kind's heading
    DUE: The day the copy is due back
---
## What it is

Two or three sentences: what the pack is for and who uses it.

## Data sources and licences

Anything the table of sources does not say (an internal-only source and why).
```

Any of the seven sections may have intro text; it comes before the generated part. **To write or check it**: a generator that builds
the pack calls `readme.render_pack(pack_dir, files=out)` with the files it is about to write and adds `README.md` to them (so its
`--check` covers it: `tools/packgen/banking/make_docs.py`, `tools/packgen/common/packbuild.py`, `tools/packgen/us_equities/make.py`); a
pack without a generator runs `python3 tools/packgen/readme.py <pack>`, and `python3 tools/packgen/readme.py --all --check` checks
every pack.

**Every command needs a data contract too.** Each kind that has a mnemonic gets `config/packs/<pack>/contracts/<kind>.contract.yaml`:
its class and status, source, grain, key, dating, cadence, storage, every field (type, presence, unit, description, example, values,
link) and its validation rules ([docs/contracts/README.md section 16](../contracts/README.md#16-contracts-of-every-command)). Unlike
the README, a contract is drafted once and then owned by people: `drishti.py contract bootstrap <kind> --write` reads the pack, its
samples and test data, its glossary and its Sutras, and lists what it inferred under `review` to confirm; then `drishti.py contract docs`
regenerates the pages. `drishti.py contract check` (and `drishti-console/tests/test_data_contracts.py`) fails when a mnemonic has no
contract, a contract is invalid, or the samples and test data drift from it, so regenerating a pack's samples with new fields means
updating its contracts in the same change.

**Every pack guide ends with its data contracts and its data retrieval (2.9.1).** A generator that writes a pack's guide appends
`guide_data.sections(pack_dir)` (`tools/packgen/common/guide_data.py`): *Data contracts*, every kind's contract with every column (type,
key, presence, unit, example or values, link), and *Data retrieval*, where each kind's data comes from (an external source with its
licence, internal-only flag and refresh script; a generator of synthetic data; or a Drishti engine), how it reaches the lake, checking
and offline. Where the data comes from is config, `tools/packgen/common/data_retrieval.yaml`: a new pack adds its default origin and
the kinds that differ (a computed kind needs nothing: its contract names its engine). A pack without a guide generator is kept by
`python3 tools/packgen/common/guide_data.py` (`--check`): compute-demo's whole guide, and a generated block at the end of the
hand-written news-and-markets guide. External sources themselves are documented on the
[External data](DATA_GUIDE.md#4-external-data-where-it-comes-from-and-how-to-refresh-it) page.

### Generating a pack from JSON Lines

`drishti.py pack new` turns existing data (one JSON document per line, or the loader envelope `{"kind","id","doc"}`) into a complete
pack without writing a generator: kinds, Sutras (one per value combination of the fields you name), samples, tests, a form, an About
skeleton, a lake or files store, and a README. Input is a folder of `*.jsonl` (`-r` for subfolders) and/or single `.jsonl` files; the
kind is the file stem unless `--kind` is given (an envelope file may hold several kinds). It uses the `sutra design --each` command of
the server's exec jar (build the server first; `--jar`/`--java` to choose). Behind it are `tools/packgen/pack_from_jsonl.py`,
`tools/sutragen.py` (Sutras only) and `tools/ingest_jsonl.py` (`drishti.py data ingest`, new days into the store). The whole command line
(`pack make`, `pack check`, `pack about-check`, `pack regenerate`, loading into a running server, the workbench's workflow) is in
[CLI_GUIDE.md](CLI_GUIDE.md); shipping a finished pack by copying a versioned, checksummed bundle (`pack bundle`, `verify`, `deploy`,
`rollback`) is in [OPERATIONALISING.md](OPERATIONALISING.md).

Worked example, run as written on 2.3.0: 300 ENDUR trades on two business days (600 rows, with a `BUSINESS_DATE` added) and the 22
counterparties they name, taken from `data/testdata/` into a folder `jsonl/` (`trade.jsonl`, `counterparty.jsonl`).

```bash
drishti-console/.venv/bin/python tools/drishti.py pack new jsonl/ --name my-bank --title "My bank" \
    --key trade=TRADE_ID,counterparty=COUNTERPARTY_ID --match trade=PRODUCT_TYPE --date BUSINESS_DATE \
    --samples 3 --fallback --out packs/my-bank --lake lake/
```

```text
622 documents, 2 kind(s), 7 group(s)
drafting 7 Sutra(s) with one `sutra design --each` run ...
kind            group                                          docs samples  sutra
counterparty    (all)                                            22       3  counterparty-default
trade           PRODUCT_TYPE=CallNot                             12       3  trade-callnot
trade           PRODUCT_TYPE=Cash                               112       3  trade-cash
trade           PRODUCT_TYPE=ComFut                             110       3  trade-comfut
trade           PRODUCT_TYPE=ComSwap                            290       3  trade-comswap
trade           PRODUCT_TYPE=Commodity                           76       3  trade-commodity
trade           (all)                                           600       3  trade-default
kind                business date        rows
counterparty        2026-09-23             22
trade               2026-09-22            300
trade               2026-09-23            300
total rows: 622
Dated store (delta): 622 rows written to lake/ (domain my-bank); 0 document(s) without a date or id skipped
pack written to packs/my-bank
```

then it runs `sutra lint` and `sutra test` on the new pack (here both exit `0`, with help warnings for the fields the About skeleton
does not explain yet: `trade-comswap: help coverage 19/35 (54%)`). What it wrote:

```text
packs/my-bank/
  pack.yaml                 code MB, mnemonics COU and TRA (initials; --mnemonic kind=ABC to choose), columns, form, connectors: [my-bank-store]
                            with its connector-templates (delta, domain my-bank, layout columns per kind), routes, ingest
  sutras/trade/             trade-callnot, trade-cash, trade-comfut, trade-commodity, trade-comswap, trade-default (.v1.sutra.yaml)
  sutras/counterparty/      counterparty-default.v1.sutra.yaml
  tests/<sutra>/            three samples each and expect.yaml
  samples/                  catalog.jsonl, trade.jsonl, counterparty.jsonl (--catalog-samples, 25 a kind by default)
  config/about.yaml         TODO glossary entries for every field the Sutras show
  generated/                what pack new generated: the base `pack regenerate` merges your edits against (keep it with the pack)
  README.md
```

```bash
DRISHTI_PACKS=my-bank DRISHTI_DELTA_ROOT=$PWD/lake java -jar drishti-server/target/drishti-server-*-exec.jar     # alone: see the note below
```

- **The folder name is the pack name.** `--out` defaults to `config/packs/<name>`; a folder with another name makes `sutra lint` refuse the
  pack (`pack.yaml declares pack 'my-bank', expected 'mybank'`).
- **Kinds must be free.** This example's kinds are `trade` and `counterparty`, which the shipped `trading` and `core` own, so it loads alone
  (`DRISHTI_PACKS=my-bank`), not beside them. For a pack that joins the seven, name your kinds yourself (`--kind`) or extend the shipped pack
  and serve its kind from your connector ([Inheritance](#worked-example-overriding-a-parent)).
- **`--key`, `--date`, `--match`** are dotted paths, one value for every kind (`--key id`) or per kind (`--key trade=TRADE_ID,counterparty=COUNTERPARTY_ID`;
  `--match trade=PRODUCT_TYPE,SOURCE_SYSTEM;counterparty=TYPE`). A match value is quoted by type in the generated `where`
  (`$.PRODUCT_TYPE == 'ComSwap'`, `$.SIZE == 3`, `$.LIVE == true`; a quote in a value is escaped); a document lacking a match field
  forms a `$.F == null` group (`--skip-missing` leaves it out). `--min-docs`, `--priority` (default 10), `--fallback` (adds
  `<kind>-default`, priority 1), `--name-prefix` and `--samples N` (deterministic; with `--date` spread across dates, newest first).
- **`--date`** names the business-date field: it is kept out of the match; the pack gets a Delta (or File) connector, `routes` and an
  `ingest:` block (`{kind: {key, date}}`, an optional tooling key read by `data ingest --pack` and ignored by the server); the lake is
  partitioned by `business_date`. Documents without the date are reported and left out of the lake and files; kinds without `--date` are
  served from `samples/`.
- **`--store files --files-root DIR`** writes the File layout (`DIR/<pack>/<date>/<kind>.jsonl`, served with `DRISHTI_FILES_ROOT=DIR`) instead
  of a lake. `--force` overwrites an existing `--out`.
- **New days later:** `drishti.py data ingest --from new/trade.jsonl --pack config/packs/my-bank --lake lake/` replaces only the dates in the
  input (`--mode overwrite-dates`, the default; `append` adds, `replace` rewrites the table; `--dry-run` prints counts per kind and date); a
  running server picks the new data up at its next refresh (Delta: 10 s, files: `rescan-seconds`), or at once with the data-load signal.
  For large exports (a book of a million trades), `drishti.py lake build` streams the input with flat memory ([TOOLS.md](TOOLS.md)).
- **New data, new shapes:** `drishti.py pack regenerate` drafts Sutras, About text and tests from the new data and three-way merges them
  into the pack against `generated/`, keeping your edits.

## Part V. Domain packs: the packs that ship, turning them on, who sees which

Drishti's core knows no industry. It knows documents, shapes, the Rachana layout grammar, inference, links and live
updates. Everything that belongs to one industry (the commands you type, the screens, the sample data, the lakes the data
lives in, the help pages, the BI datasets) arrives in a **domain pack**: one folder under `config/packs/`.

This guide is for users and administrators. It explains, with examples checked on a running server:

1. [What a pack is](#what-a-pack-is), and [every pack that ships](#the-packs-that-ship), with its commands, its data and
   [the trading pack's several source systems](#trading).
2. [Where the seven packs read their data](#the-demo-lakes-where-the-seven-packs-read-their-data): the Delta lakes that one
   command builds from the committed test data.
3. [How to load packs](#turning-packs-on), [switch them off and on for everyone](#switching-packs-off-and-on-admin--packs)
   and [who sees which pack](#who-sees-which-pack).
4. [Where a pack reads its data](#packs-and-connectors-where-a-pack-reads-its-data): the connector files that packs name,
   and [the BI datasets every pack gives you](#bi-over-the-packs-kinds).
5. [Loading a pack while the server runs](#loading-a-pack-while-the-server-runs-1), [pack codes](#pack-codes) and
   [upgrading or removing a pack](#upgrading-and-removing-a-pack).

**Writing a pack** (the manifest key by key, links, roles, Sutras, samples, tests, inheritance, generators, the signed
registry, exporting from the Build workbench) is in the [Pack developer guide](PACK_DEVELOPER_GUIDE.md), which builds a help-desk
pack step by step.

### What a pack is

A pack is configuration and content only. It contains no Java and no Python that the server runs (its Calc snippets,
`python/*.py`, run in the user's browser; see [Calc](#calc-python-snippets)). Here is a small
complete pack, the help-desk pack the [Pack developer guide](PACK_DEVELOPER_GUIDE.md) builds
(`docs/guides/examples/pack/helpdesk/`):

```text
helpdesk/
├── pack.yaml                    the manifest: kinds, commands, links, columns, roles, alerts, connectors, console extras
├── sutras/
│   └── ticket.v1.sutra.yaml     one layout (a Sutra, in YAML) for tickets
├── tests/
│   └── ticket/                  two sample tickets and expect.yaml: what `sutra test` checks about the Sutra
├── config/
│   ├── semantics.yaml           field-name hints and labels for inference ("AGE_HOURS" is an age, in hours)
│   ├── formats.yaml             a number format of the pack (30 → "30 h")
│   ├── about.yaml               what a ticket is and what each of its fields means (About this page)
│   ├── workspaces.yaml          a starter workspace ("Ticket desk")
│   └── help.yaml                the pack's card in the help centre
├── guides/
│   └── helpdesk.md              the guide that card opens
├── python/
│   └── queue-by-status.py       a Calc snippet
└── samples/
    ├── catalog.jsonl             the list of sample entities (kind, id, title, subtitle)
    ├── ticket.jsonl             the sample tickets, one JSON document a line
    ├── agent.jsonl
    └── client.jsonl
```

When the server runs with this pack and its sample data (the demo source, `DRISHTI_DEMO_ENABLED=true`, the default), you can
type `TKT TKT-1001 <GO>` and see a ticket. Without the pack, `TKT` means nothing.

A shipped pack is the same shape, larger. The trading pack, for example, holds 134 Sutras in five folders and six shared
panel blocks, one `samples/trade.jsonl` of 765 trades, a test folder for each Sutra and the templates of the five connectors it
reads through ([Trading](#trading)).

**Every attribute is UPPER_SNAKE.** The shipped packs name every attribute of every kind (counterparties, curves, netting
sets, trades, economies, news topics) the way production systems write them: `COUNTERPARTY_ID`, `NET_MTM`, `ZERO_RATE`,
`TRADE_ID`, `GDP_USD_BN`. Searches, forms, Sutras, About text, BI datasets and the test data all use those names
(`TRD PRODUCT_TYPE=ComSwap`, `CPTY COUNTRY_OF_RISK=US`).

Words used below:

| Word | Meaning | Example |
|---|---|---|
| **kind** | a type of entity | `trade`, `netting-set`, `ticket` |
| **mnemonic** | the short command for a kind | `TRD` opens a `trade` |
| **id** | one entity's identifier | `MX-20000001`, `1000001` |
| **Sutra** | a layout for a kind, written in Rachana: one YAML file starting `rachana: 1` | `sutras/murex/irs-fixfloat.v1.sutra.yaml` |
| **connector** | a configured source of data, owned by the site: one YAML file in `config/connectors/` whose file name is its name; a pack names the connectors it reads through | `credit-store` (a Delta Lake folder) |
| **route** | which connector answers a kind | `netting-set: credit-store` |
| **template** | a pack's suggestion for a connector, written to the connector file once, at the first start | the `connectors:` mapping of a shipped pack |
| **union** | several connectors serving one kind together, each holding its own part (one per source system) | `trade-endur`, `trade-calypso`, `trade-murex` |

### The packs that ship

Eleven packs ship in `config/packs/`; seven are on by default (`drishti.packs.enabled`, below), `us-equities`, `gleif` and `funds` are turned on by adding them to `DRISHTI_PACKS`, and `compute-demo` (a [sample pack](#sample-packs) for reactive connectors) likewise. Each row lists the
pack, its code (typed alone it opens the pack's overview), what it inherits from, its mnemonics, and commands to try. The
example ids are real: they are in the pack's `samples/` and in the [demo lakes](#the-demo-lakes-where-the-seven-packs-read-their-data).

| Pack | Code | Extends | Mnemonics | Try |
|---|---|---|---|---|
| `core` | `CORE` | — | `CPTY` counterparty (with `COUNTRY_OF_RISK` and `UEN`) · `GRP` counterparty group · `ISS` issuer · `CCP` central counterparty · `LE` bank legal entity · `BOOK` book (portfolio) · `DESK` desk · `TRDR` trader · `CAL` holiday calendar · `CLR` clearing account | `CPTY CP-NORTHBRIDGE`, `LE LE-NY` |
| `market-data` | `MKT` | `core` | `CRV` interest rate curve · `REPO` repo curve · `FX` FX spot rate · `FXF` FX forward curve · `FXV` FX volatility surface · `IRV` swaption volatility cube · `CPV` cap/floor volatility surface · `EQ` equity · `EQX` equity index · `DIV` dividend curve · `EQV` equity volatility surface · `CDS` credit curve · `INF` inflation index · `INFC` inflation curve · `CMD` commodity · `CMDC` commodity forward curve · `CMDV` commodity volatility surface · `FIX` rate index fixings · `BND` bond (security master) · `CORR` correlation matrix | `CRV CRV-USD-OIS`, `FXV FXV-EURUSD` |
| `trading` | `TRDS` | `core`, `market-data` | `TRD` trade, from three source systems (ENDUR, CALYPSO, MUREX) in one production schema · `CTRD` counterparty trades · `PTRD` portfolio trades · `LTRD` legal entity trades · `ITRD` index trades (the last four derived from the trades of every system) | `TRD MX-20000001`, `TRD 1000001`, `CTRD CP-ALDERSHOT` |
| `counterparty-risk` | `CCR` | `trading` | `AGR` master agreement (ISDA) · `CSA` credit support annex · `NSET` netting set · `LIM` credit limit · `EXP` exposure profile · `CVA` CVA / XVA · `SACCR` SA-CCR exposure · `COLL` collateral balance · `MC` margin call · `SIMM` ISDA SIMM initial margin | `AGR AGR-NORTHBRIDGE-ISDA`, `NSET NS-SUMMIT-NY` |
| `market-risk` | `MRSK` | `market-data`, `trading` | `VAR` VaR / expected shortfall · `SCN` stress scenario · `STR` stress result · `FRTB` FRTB sensitivities · `PNL` P&L explain · `TVAL` trade valuation (MTM, P&L 1d, DV01 of every trade) | `VAR VAR-RATES`, `TVAL TVAL-MX-20000001` |
| `world-economy` | `WRLD` | — | `ECON` economy · `MACRO` macro indicator · `FCST` IMF forecast · `LABR` labour market · `FISC` fiscal position · `ECG` country group · `REGE` region economy · `JUR` jurisdiction · `PARTY` party · `OFFC` office holder · `ELEC` election · `REGN` world region · `SOCI` social indicator (real public data for about 216 countries: World Bank, IMF, CIA World Factbook, UNDP; [REAL_DATA](DATA_GUIDE.md#the-world-economy-data-in-detail); not a sample, but the IMF part is for internal demonstrations only) | `ECON ECON-IND` |
| `news-and-markets` | `NWMK` | — | `TOPIC` news topic · `NEWS` headlines · `CO` listed company · `QUOTE` quote and price history (fifteen topics and twenty well-known companies; headlines from the [news connector](../connectors/NEWS.md), quotes from the [yfinance connector](../connectors/YFINANCE.md); a sample pack, both connectors off as shipped; topics and companies also from the demo lake, connector `news-store`, and offline the quotes and headlines too, from a committed snapshot: [offline mode](#news-and-markets-and-world-economy-offline)) | `TOPIC TOPIC-FED` |
| `us-equities` | `USEQS` | — | `USEQ` US listed company · `IDX` index members · `SIC` SIC sector · `EDGAR` filings browser · `FILING` SEC filing · `FACT` financial fact · `PXH` price history (real public data on the Dow 30, Nasdaq-100 and S&P 500 companies: SEC EDGAR, GLEIF, Wikidata; **off by default**, add it to `DRISHTI_PACKS`; prices are live only, off and for internal use: [its guide](../../config/packs/us-equities/guides/us-equities.md)) | `USEQ AAPL`, `IDX SP500` |
| `gleif` | `GLEIF` | — | `LEI` legal entity · `LEIG` ownership group · `LEIR` parent relationship · `LEIC` country · `LEIM` ISIN, BIC or MIC to LEI (real public data from GLEIF, CC0: the ownership groups of the us-equities companies and of the large banks, insurers, asset managers and market infrastructures, at most 50 entities a group; **off by default**, add it to `DRISHTI_PACKS`; with `us-equities` on, a company and its LEI record link both ways: [its guide](../../config/packs/gleif/guides/gleif.md)) | `LEI 8I5DZWZKVSZI1NUHU748`, `LEIG ALL` |
| `funds` | `FUNDS` | — | `FUND` fund or ETF · `HELD` funds holding a company · `TRACK` funds tracking an index (real public data from the SEC, public domain: the 200 largest US mutual funds and ETFs by net assets with their holdings from Form N-PORT, census facts from Form N-CEN, fees and returns from the prospectus summaries; **off by default**, after `us-equities`, whose companies its holdings open; [its guide](#part-vi-the-funds-and-etfs-pack)) | `FUND QQQ`, `HELD AAPL`, `TRACK SP500` |
| `compute-demo` | `CMPD` | — | `PBOOK` path book · `PSTRIP` P&L strip · `PSUM` book path computed by the sample Python engine · `PSUMJ` the same by the Java engine (synthetic numbers; a sample pack, off unless named in `DRISHTI_PACKS`) | [tutorial 23](../tutorials/23-computed-panels.md) |

What each pack holds, as shipped (counted in `config/packs/`):

| Pack | Kinds | Sutras (and shared blocks) | Sample documents | Sutra test folders | Calc snippets | Connectors it names (plugin) |
|---|---|---|---|---|---|---|
| `core` | 10 | 10 | 129 | 0 | 10 | `reference-store` (delta) |
| `market-data` | 20 | 20 | 220 | 0 | 27 | `market-store` (delta); `nyfed-sofr-feed`, `ecb-estr-feed`, `ecb-fx-feed`, `us-treasury-feed`, `fred-feed` (feeds) |
| `trading` | 5 | 134 (+ 6 blocks) | 765 | 134 | 9 | `trade-endur`, `trade-calypso`, `trade-murex` (delta, a union); `trading-stream` (kafka, off); `trade-pages` (derived) |
| `counterparty-risk` | 10 | 10 | 534 | 8 | 12 | `legal-store`, `credit-store`, `collateral-store` (delta) |
| `market-risk` | 6 | 6 | 923 | 5 | 13 | `risk-store` (delta) |
| `world-economy` | 13 | 13 | 9,582 | 13 | 0 | `macro-store`, `civic-store` (delta) |
| `news-and-markets` | 4 | 4 | 35 | 0 | 0 | `news` (news), `yfinance` (yfinance), `news-store` (delta) |
| `us-equities` | 7 | 8 (+ 4 fragments) | 13,504 | 0 | 0 | `us-equities-prices` (yfinance, off, internal use only) |
| `gleif` | 5 | 7 (+ 2 fragments) | 22,169 | 0 | 0 | none: its samples are its data |
| `funds` | 3 | 3 (+ 1 fragment) | 3 | 0 | 0 | `funds-store` (delta) |
| `compute-demo` | 4 | 1 | 123 | 0 | 0 | `compute-demo-store` (delta); `path-sum-python`, `path-sum-java` (reactive) |

**Links between them.** A `core` counterparty carries its country of risk (`COUNTRY_OF_RISK`, ISO 3166 alpha-2, as the trading
systems name it on a trade: `COUNTERPARTY_COUNTRY_OF_RISK`) and its `UEN`, and two link fields that open other packs when they are on:
`ECONOMY` (`ECON-<alpha-3>`, the country of risk's economy in `world-economy`) and `NEWS_ID` (its headlines in `news-and-markets`). A
country topic and a listed company of `news-and-markets` link to their economy in the same way. Agreements and CSAs belong to
`counterparty-risk`, so `CPTY CP-NORTHBRIDGE` shows its master agreement as a link only when `counterparty-risk` is on.

#### Trading

**Ids are text; numeric ids in files are read as text.** `TRADE_ID` and every other id (`LEG_ID`, `FLOW_ID`, `RESET_ID`, `EVENT_ID`, `TRADE_IDS`) is a string in the test data and samples (ENDUR's are digits, `"1000008"`). A real extract that writes an id as a JSON number (`1000008`) is read as `"1000008"`, never `1000008.0`; text ids keep their leading zeros; no id prefix or shape is assumed (`SWP-AB_12`, `mx_2000-01`, `007123` are all ids) — `SOURCE_SYSTEM` says which system a trade comes from. An older lake whose `TRADE_ID` column was written as a number is still read (as text).

A bank books its trades in several systems; the trading pack shows them as one book. Each system feeds a **common schema**: the
47 UPPER_SNAKE attributes a production extract has, nested the same way whatever the system:

| Level | Attributes |
|---|---|
| deal | `SOURCE_SYSTEM` (ENDUR, CALYPSO or MUREX), `SOURCE_UPDATE_TIME`, `TRADE_ID` (unique across the systems: Endur deal numbers `1000001`…, Calypso `CLY-…`, Murex `MX-…`), `TRADE_STATUS`, `TRADE_ACTION`, `TRADE_VERSION`, `TRANSACTION_ID`, `PACKAGE_ID`, `PRODUCT_TYPE` (picks the deal's screen), `PRODUCT_SUB_TYPE`, `PRODUCT_GROUP`, `PRODUCT_SUB_GROUP`, `QUANTITY`, `PRICE`, `BUY_SELL_CODE`, `OPTION_TYPE`, `STRIKE_PRICE`, `COUNTERPARTY` (a `core` counterparty id), `COUNTERPARTY_UEN`, `COUNTERPARTY_COUNTRY_OF_RISK`, `ORIGINAL_COUNTERPARTY`, `LEGAL_ENTITY`, `PORTFOLIO` (a `core` book), `TRADER_ID` and the trader's names, the dates (`TRADE_DATE`, `INPUT_DATE`, `START_DATE`, `MATURITY_DATE`, `SETTLEMENT_DATE` …), `CURRENCY` and the flags (`DELIVERY_VERSUS_PAYMENT`, `PRECIOUS_METAL_FLAG` …), then `LEGS` and `EVENTS` |
| `LEGS[]` | `LEG_ID`, `LEG_TYPE` (FIXED, FLOAT, PHYSICAL, OPTION), `LEG_ECONOMIC_TYPE` (Pay, Receive), `NOTIONAL`, `PRICE_UNIT`, `DISCOUNT_INDEX`, reset conventions, `SETTLEMENT_TYPE`, `INDEX {NAME, GROUP, SUB_GROUP}`, `CASH_FLOWS[]` |
| `CASH_FLOWS[]` | `FLOW_ID`, `NOTIONAL`, `RATE`, `FLOAT_SPREAD`, `START_DATE`, `END_DATE`, `PAYMENT_DATE`, `RATE_DETERMINATION_DATE`, `STRIKE_PRICE`, `RESETS[]` (`RESET_ID`, `RESET_DATE`, `RATE` …) |
| `EVENTS[]` | `LEG_ID` (blank for the deal's own events), `EVENT_ID`, `EVENT_TYPE`, `PAYMENT_TYPE`, `EVENT_DATE`, `AMOUNT` (signed: paid is negative), `CURRENCY` |

Endur books the commodity desk's own deals (five product types: `ComSwap`, `ComFut`, `Cash`, `CallNot`, `Commodity`); Calypso
(51 product types) and Murex (74) book the other products of the taxonomy (rates, FX, credit, equity, commodities, inflation, fixed
income, securities financing, money markets, structured). Missing values are spelt as extracts spell them (mostly `""`), so the
screens read them with `nonblank`.

**Three connectors, one kind.** Each system lands in a Delta table of its own (`<root>/trading/trade_endur`, `trade_calypso`,
`trade_murex`, partitioned by business date and `PRODUCT_TYPE`), read by its own connector (`trade-endur`, `trade-calypso`,
`trade-murex`, each with `union: true` and its `table:`), all serving `trade`: opening a trade finds it in whichever system holds it, a
search, a type-ahead, the search form's drop-downs and a BI table over `trade` read all three, and every row says its
`SOURCE_SYSTEM` ([CONNECTORS.md](../connectors/CONNECTORS.md#several-connectors-for-one-kind-a-union)). On the demo lakes,
`TRD MX-200000 <GO>` finds 99 trades among the 15,000 of the three systems, with the pack's pick-list columns:

```text
TRD          Source system  Product type   Trade status  Trade date   Buy sell code  Currency  Quantity     Maturity date  Portfolio     Counterparty
MX-20000001  MUREX          IRS_FIXFLOAT   AMENDED       2025-07-25   S              AUD       242,000,000  2032-06-25     BOOK-RATES-3  CP-MERIDIAN-RE
MX-20000002  MUREX          IRS_FIXFLOAT   VALIDATED     2025-02-06   B              EUR       110,000,000  2031-04-15     BOOK-RATES-1  CP-HALCYON
MX-20000003  MUREX          IRS_FIXFLOAT   VALIDATED     2026-09-18   B              USD       204,000,000  2036-05-13     BOOK-RATES-1  CP-AURORA
…
```

`drishti.py lake demo` builds the three tables from the committed test data ([the demo lakes](#the-demo-lakes-where-the-seven-packs-read-their-data));
for your own extracts, `drishti.py lake build --connector trade-murex …` writes one ([TOOLS.md](TOOLS.md)).

**The deal screen** (one per product type, the same layout for all): **F2 DES** the deal ticket; **F3 LEGS** the legs side by side,
each with its terms, its notional profile (a step line), its fixings and its cash flows opening to their resets; **F4 FLOWS** every
flow of every leg; **F5 EVT** the events by leg, with a table filtered by leg and by dates (the header's controls); **F6 PAY** the
netted payment ladder and payments by month; **F7** the counterparty's trades; **F8** impact; **F9** the raw document.

**Where its Sutras are.** One Sutra per source system and product type, matching
`"$.SOURCE_SYSTEM == 'ENDUR' && $.PRODUCT_TYPE == 'ComSwap'"` (priority 10); a product's sub types (`ENGY-SWAP`, `ENGY-B-SWAP`) are
panels with `when:` inside it, never Sutras of their own, so every trade matches exactly one. They are grouped in lower-case folders
for people only: Drishti reads `sutras/` recursively and infers nothing from a folder name (each Sutra's `match:` alone decides).

| Folder | Holds |
|---|---|
| `sutras/common/` | the six shared panel blocks every product Sutra `use:`s: `deal-ticket`, `legs`, `flow-schedule`, `events`, `payments`, `deal-footer` (`*.v1.block.yaml`; [Shared panel blocks](RACHANA_REFERENCE.md#shared-panel-blocks-use)) |
| `sutras/endur/` | Endur's five product types (`endur-comswap.v1.sutra.yaml` …), each with its sub types' own panels |
| `sutras/calypso/` | the 51 product types Calypso books (`govt-bond`, `cds-single`, `fx-option` …) |
| `sutras/murex/` | the 74 product types Murex books (`irs-fixfloat`, `xccy-swap`, `swaption-eur` …) |
| `sutras/pages/` | the counterparty, portfolio, legal-entity and index pages (`CTRD`, `PTRD`, `LTRD`, `ITRD`) |

Admin → Packs → *trading* → **Sutra coverage** lists each source system × product type × sub type in the data with how many trades
have it, the Sutra that lays it out and the panels its `when`s show, flagging *none* (an inferred screen) and *several*. On the demo
lakes it finds 132 combinations, every one laid out by exactly one Sutra (`GET /api/v1/packs/trading/coverage?kind=trade` answers
`combinations: 132, none: 0, several: 0`); the same check is a test (`SutraCoverageTest`, and the generators' `test_trades.py` for every
product and sub type they book).

**Pages over many trades** (derived kinds, rebuilt from the trades of every system by the `trade-pages` connector when a load
changes the data; kept on disk, `store: disk`, so they are complete at any number of trades and served at once after a restart):
`CTRD <counterparty>`, `PTRD <book>`, `LTRD <legal entity>`, `ITRD <index>`. Each shows the live trades (at most 1,000 listed; the
count and every total cover all of them; with a Pivot tab by product and system), payments in the next 7, 30 and 90 days (over every
trade) and the upcoming ones listed, notional by product type (a donut), by index, the
maturity ladder by year and payments by month. On the demo lakes the pack overview (`TRDS <GO>`) counts 15,000 trades, 24
counterparties, 30 portfolios, 4 legal entities and 57 indexes.

**Values are the risk engine's.** A booking carries no mark-to-market; `market-risk`'s **trade valuation** (`TVAL TVAL-<trade
id>`) carries each trade's MTM, P&L 1d and DV01 with the trade's source system, portfolio, desk and netting set, and its MTM ticks
live. The BI samples that add up MTM (`trades-basic`, `trading-risk`, `live-trading`) read the valuations; `trading-cashflows`
reads the bookings of the three systems.

Every pack's overview guide (*Help → Domain packs*, or `config/packs/<name>/guides/`) describes its domain and has a
**Finding things** section with the commands that work for every kind of the pack:

| Command | Does |
|---|---|
| `CPTY <id> <GO>` | Opens that counterparty. A bare identifier works too: its prefix (`CP-…`) tells Drishti the kind. |
| `CPTY <start of an id> <GO>` | A pick list: one match opens, several give a table with the kind's key fields. `*` is a wildcard, and case never matters. |
| `CPTY <field>=<value> <GO>` | Lists by field value (`CPTY COUNTRY_OF_RISK=US`). Compare with `<` and `>`, combine with `and`, sort with `order by <field> desc`. |
| `CPTY <GO>` | Lists every counterparty. |

(Every pack guide has the same table for its own kinds. Pick lists are explained in
[USER_GUIDE.md](USER_GUIDE.md#pick-lists-when-a-command-names-several-entities).)

Typical combinations:

| You want | `DRISHTI_PACKS=` |
|---|---|
| Everything that ships (the default) | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets` |
| The risk packs only | `market-risk,counterparty-risk` |
| Trades and their reference data | `trading` |
| Only the world economy | `world-economy` |
| Everything, with the US equities pack | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities` |
| Everything, with US equities and their LEIs | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif` |
| Everything, with the 200 largest US funds and ETFs too | `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds` |

You do not have to list `core`, `market-data` or `trading`: the packs that extend them bring them in (see
[Inheritance](#inheritance)).

#### Retired example packs (examples/packs)

Seven earlier packs moved to `examples/packs/` in 2.3.0. They are not shipped in the images and not tested by default (one test,
`AllShippedPacksTest`, checks that each still loads on top of the seven), and they keep their own attribute names. They still load:
name the folder in `drishti.packs.extra-dirs` (`DRISHTI_PACKS_EXTRA_DIRS`) and the pack in `drishti.packs.enabled`. The shipped packs
win a name, and a retired pack may extend them (`retail-banking` extends `core`, `climate-risk` extends `trading`).

| Pack | What it was | Load it with |
|---|---|---|
| `finance` | the small demo behind the four original mockups (`TRD IRS-48213`); it defines `trade` and `counterparty` itself, so it is an alternative to the seven, not an addition | `DRISHTI_PACKS_EXTRA_DIRS=examples/packs DRISHTI_PACKS=finance` |
| `logistics` | shipments, containers, vessels, ports (`SHP SHP-10042`) | `DRISHTI_PACKS_EXTRA_DIRS=examples/packs DRISHTI_PACKS=finance,logistics` |
| `genomics` | genes, variants, proteins, pathways (`GENE GENE-TP53`) | `… DRISHTI_PACKS=<the seven>,genomics` |
| `retail-banking` | customers, accounts, mortgages, cards, IFRS 9 segments (`CUST CUST-100231`) | `… DRISHTI_PACKS=<the seven>,retail-banking` |
| `operational-risk` | loss events, RCSA, KRIs, SMA capital (`LOSS LOSS-2025-0001`) | `… DRISHTI_PACKS=<the seven>,operational-risk` |
| `climate-risk` | climate profiles, PCAF, NGFS stress (`CLIM CLIM-SOLARIS`) | `… DRISHTI_PACKS=<the seven>,climate-risk` |
| `liquidity-risk` | LCR, NSFR, ladders, HQLA (`LCR LCR-NY`) | `… DRISHTI_PACKS=<the seven>,liquidity-risk` |

Four packs were merged into the seven and have no copy of their own: `banking-core` is `core`; `economics` and `politics-society`
are `world-economy`; `news-watch` and `market-watch` are `news-and-markets`. Their kinds, mnemonics and ids are unchanged, so
links, saved views and BI items that name a kind keep working; a list that names an old pack (`DRISHTI_PACKS=economics`) must
name the new one. Agreements and CSAs moved from `banking-core` to `counterparty-risk`, and their Delta tables from the
`reference` domain to the `legal` domain (`<lake>/legal/agreement`, `<lake>/legal/csa`): `drishti.py lake demo` builds them there.

### The demo lakes: where the seven packs read their data

Every kind of the seven packs (except news headlines and quotes, which come from the internet) is served by a **Delta connector**:
a folder of Delta tables, one per kind, under `<root>/<domain>/`. The root is `${DRISHTI_DELTA_ROOT:<data>/delta}` (the data folder,
`drishti.data.dir`, is `./data` unless you move it). The domains are the packs' connectors:

| Connector | Domain folder | Pack | Kinds |
|---|---|---|---|
| `reference-store` | `reference/` | core | the ten kinds of `core` |
| `market-store` | `market/` | market-data | the twenty kinds of `market-data` |
| `trade-endur`, `trade-calypso`, `trade-murex` | `trading/trade_endur`, `trade_calypso`, `trade_murex` | trading | `trade`, one table per source system |
| `legal-store`, `credit-store`, `collateral-store` | `legal/`, `credit/`, `collateral/` | counterparty-risk | agreements and CSAs; netting sets, limits, exposure, CVA, SA-CCR; collateral, margin calls, SIMM |
| `risk-store` | `risk/` | market-risk | the six kinds of `market-risk` |
| `macro-store`, `civic-store` | `macro/`, `civic/` | world-economy | the economic kinds; the political and social kinds |
| `news-store` | `news/` | news-and-markets | `news-topic`, `listed-company`; and `quote`, `news` from the captured market snapshot (offline) |

The committed test data (`data/testdata/`, sixty business days of every dataset, 5,000 trades per source system) becomes those
tables with **one command** (or the IDE run configuration **Build demo lakes**):

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

```text
  trading/trade_endur: … rows over 60 days
  trading/trade_calypso: … rows over 60 days
  trading/trade_murex: … rows over 60 days
  legal/agreement: … rows over 60 days
  …
  news/listed-company: … rows over 60 days
  news/news-topic: … rows over 60 days

Demo lake ready: …/data/delta: 85 tables, 60 business days 2026-07-08 to 2026-09-30, built in 943 s
Feeds: …/data/feeds/fixing.jsonl (the file connector's sample feed)
Server: no server at http://127.0.0.1:19590: left …/data/bi-store/lake-rebuilt.json, so a server clears its stored BI columns and rollups at its next start
Connector files:
  civic-store: generated by the server at its first start (reads …/data/delta)
  …
```

(The table lines were captured on a clean clone of 2.5.0, on the seven-day lakes, with `--server` naming a port where nothing ran, so
their row counts are left out; the summary line is from a sixty-day run of 2.6.5 that kept the test data's own dates: 943 s, about
3.4 GB on disk.) By default the sixty days are moved to end on today's business date, so every page opens on data; `--as-of data` keeps the test data's own dates.
Start the server with the samples off so that every page reads the lakes: the run configuration **Drishti server (demo lakes)**, or
`DRISHTI_DEMO_ENABLED=false` with the seven packs. Running `lake demo` again while a server runs is safe: it sends the data-load
signal for every table and date it wrote (`--server`, `--token-file`), and a running server also sees each rebuilt Delta date by
itself and reads it again, so BI never shows old figures. The steps, the demo pages and the timings are in
[data/testdata/README.md](../../data/testdata/README.md); every option of `lake demo` and `lake build` is in [TOOLS.md](TOOLS.md).

#### news-and-markets and world-economy offline

A demonstration often runs on a laptop with no internet. Both packs then work fully from the repository:

- **news-and-markets.** Its quotes (`QUOTE-<ticker>`, Yahoo Finance through the `yfinance` connector) and headlines (`NEWS-<name>`,
  Google News through the `news` connector) are computed live, so the repository keeps a **real snapshot** of them:
  `data/testdata/news-and-markets/quote/` (the twenty companies, a year of daily bars each) and `news/` (the companies' and the
  fifteen topics' headlines, filtered and `~all`), captured through the pack's own connectors by `drishti.py testdata capture-market`.
  `lake demo` builds them into `news/quote` and `news/news`, which the pack's `news-store` connector serves beside the topics and
  companies. Every page reads the same documents it reads live (same Sutras, same panels); a quote's *As of* and the headlines'
  *Fetched* end in *(snapshot captured <date>)*, and the quote and headline pages add a **Snapshot** note. The data belongs to its
  providers and is shown for demonstration only. Refresh it with `tools/publicdata/news_and_markets.py` ([PUBLIC_DATA](DATA_GUIDE.md#4-external-data-where-it-comes-from-and-how-to-refresh-it)).
- **world-economy.** Already offline: all thirteen kinds (`economy`, `macro-indicator`, `economic-forecast`, `labour-market`,
  `fiscal-position`, `country-group`, `region-economy`, `jurisdiction`, `party`, `office-holder`, `election`, `region`,
  `social-indicator`) are the pack's samples (real World Bank, IMF, CIA World Factbook and UNDP figures captured when the pack was
  built; [REAL_DATA](DATA_GUIDE.md#the-world-economy-data-in-detail); refresh with `tools/publicdata/world_economy.py`, [PUBLIC_DATA](DATA_GUIDE.md#4-external-data-where-it-comes-from-and-how-to-refresh-it)), served by `macro-store` and `civic-store` from the demo lake. The pack has no live connector.
- **Offline mode.** Start the server with `DRISHTI_OFFLINE=true` (the run configuration **Drishti server (demo lakes)** sets it):
  the `news`, `yfinance` and public-data `feed` connectors never start, even when a file enables them, so nothing waits on the
  network and Admin → Connectors shows them **off (offline mode)** while `news-store`, `macro-store` and `civic-store` run
  ([CONFIGURATION](../admin/CONFIGURATION.md#drishtioffline--offline-mode-no-internet)). Without it the snapshot still answers
  whenever the live connectors are off (their shipped state); with them on and the internet there, the live connector is asked first.

Refresh the snapshot on a machine with internet: `drishti.py testdata capture-market` (about four minutes: two seconds between calls),
then `drishti.py lake demo` and commit `data/testdata` ([data/testdata/README.md](../../data/testdata/README.md#the-market-snapshot-news-and-markets-offline)).

### Turning packs on

The server reads one setting, `drishti.packs.enabled`, from the environment variable `DRISHTI_PACKS` (a
comma-separated list; default the seven default packs, `core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets`).
Packs are looked for in `drishti.packs.dir` (`DRISHTI_PACKS_DIR`, default `./config/packs`, relative to the directory you start the
server in), then in the folders of `drishti.packs.extra-dirs` (`DRISHTI_PACKS_EXTRA_DIRS`, comma-separated, default none; set it to
`examples/packs` for the [retired example packs](#retired-example-packs-examplespacks)).

1. Stop the server.
2. Start it with the packs you want. From the repository root:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   DRISHTI_PACKS=market-risk,world-economy java -jar drishti-server/target/drishti-server-2.9.6-exec.jar
   ```

   (or, from the IDE, the run configuration **Drishti server** with `DRISHTI_PACKS` changed; [GETTING_STARTED.md (IDE)](GETTING_STARTED.md#running-from-intellij-idea-and-pycharm)).
3. Check what loaded. With security on, an API token goes in the header (`-H "Authorization: Bearer $DRISHTI_TOKEN"`;
   [API_GUIDE.md (clients)](API_GUIDE.md#clients-scripts-notebooks-and-excel) says how to make one):

   ```bash
   curl -s http://localhost:18480/api/v1/packs -H "Authorization: Bearer $DRISHTI_TOKEN" \
     | python3 -c 'import json,sys; [print(p["name"], p["version"]) for p in json.load(sys.stdin)]'
   ```

   You see one line per loaded pack that is switched on, parents included (a pack an admin switched off is missing here;
   *Admin → Packs* lists every pack with its status). With `DRISHTI_PACKS=market-risk,world-economy`, five lines:
   `market-risk`, `core`, `market-data`, `trading` and `world-economy`, each `1.0.0`. With the default seven:

   ```text
   core 1.0.0
   market-data 1.0.0
   trading 1.0.0
   counterparty-risk 1.0.0
   market-risk 1.0.0
   world-economy 1.0.0
   news-and-markets 1.0.0
   ```

   In the console, *Admin → Packs* lists the loaded packs, and *Admin → Health* (`/admin/health`) shows each
   pack with its status, what it extends, its kinds, its Sutras, its connectors and any problems.
4. Open the terminal (`/t`) and type a command from the new pack, for example `ECON ECON-IND <GO>`.

The console needs no setting: it asks the server which packs are on. (`packs.enabled` in
`drishti-console/config/application.yaml` is only a fallback for when the server cannot be reached.) If you moved the
packs folder, set `DRISHTI_PACKS_DIR` (and `DRISHTI_PACKS_EXTRA_DIRS`) for both processes.

What happens at start-up:

- Each listed pack is read from `config/packs/<name>/pack.yaml` (or the first extra folder that has it), and so is every pack it extends.
- The packs' contents become **lowest-precedence** configuration. Anything the site sets in
  `drishti-server/src/main/resources/application.yaml`, an external `application.yaml` or an environment variable
  overrides a pack.
- The server **refuses to start**, with a message naming the packs, when:
  - a listed pack folder or its `pack.yaml` is missing (`pack 'x' not found at config/packs/x/pack.yaml (required by 'y')`);
  - `pack:` inside `pack.yaml` does not match the folder name;
  - packs extend each other in a cycle;
  - two packs own the same kind (`kind trade is defined by both pack 'finance' and pack 'trading'`);
  - two **unrelated** packs define the same mnemonic, link field, badge, role, connector or route differently.

### Switching packs off and on (Admin → Packs)

`DRISHTI_PACKS` decides which packs the server **loads** at start-up. Once it runs, an administrator can
switch any loaded pack **off** for everyone, and back **on**, without a restart: *Admin → Packs* in the
console (`/admin/packs`).

| Status on the page | Meaning | What an admin can do |
|---|---|---|
| **on** | Loaded and in use | **Switch off** (unless another switched-on pack needs it) |
| **off** | Loaded, but switched off for everyone | **Switch on** |
| **not loaded** | A folder under `config/packs/` that `DRISHTI_PACKS` did not name | **Load** (below) |

What switching off does, at every user's next click:

- the pack's kinds cannot be opened (`DRS-5002`), and links to them show disabled;
- its mnemonics, suggestions, examples, guides, starter workspaces and monitors disappear;
- it leaves every user's pack menu, and `GET /api/v1/packs` stops listing it.

Its Sutras, connectors and routes stay loaded, so switching it back on is instant.

**The rules.**

1. **A pack that a switched-on pack builds on cannot be switched off.** `trading` is needed by
   `counterparty-risk` and `market-risk`. Its button is greyed (*Needed by …: switch those off first*), and the API refuses with
   `DRS-5001 'trading' is needed by [counterparty-risk, market-risk]; switch those off first`.
2. **Switching a pack on switches on everything it builds on.** With `trading` and `market-risk` both off,
   switching `market-risk` on switches `trading` on too (and `market-data` and `core`, if they were off).
3. **Packs that were not loaded cannot be switched on**: `DRS-5001 'finance' is not loaded; installed: [core, market-data, trading,
   counterparty-risk, market-risk, world-economy, news-and-markets]` (packs load at start, or with **Load**, below).

**Where the choice is kept.** In the identity database, table `drishti_pack_state` (one row per pack that was
ever switched: `name`, `enabled`, `updated_at`, `updated_by`). It survives restarts. A pack with no row is on.
Each server keeps it in memory and re-reads it every `drishti.identity.refresh-seconds` (15), so with several
servers sharing one database a switch applies at once on the server that made it and within 15 seconds on the
others (roles defined in Admin → Roles behave the same). Each switch is audited as `pack-enabled` or
`pack-disabled`, with the admin's name, in *Admin → Audit log*.

**For programs** (an admin's token when security is on):

```bash
curl -s http://localhost:18480/api/v1/admin/packs -H "Authorization: Bearer $DRISHTI_TOKEN" | python3 -c '
import json,sys
for p in json.load(sys.stdin): print(p["name"], "loaded" if p["loaded"] else "NOT LOADED", "on" if p["enabled"] else "off", p.get("requiredBy") or "")'
```

On a server started with the seven packs you see:

```text
core loaded on ['market-data', 'trading', 'counterparty-risk', 'market-risk']
market-data loaded on ['trading', 'counterparty-risk', 'market-risk']
trading loaded on ['counterparty-risk', 'market-risk']
counterparty-risk loaded on
market-risk loaded on
world-economy loaded on
news-and-markets loaded on
```

To switch a pack, `PUT /api/v1/admin/packs/<name>` with `{"enabled": false}` or `{"enabled": true}`. The answer
lists the packs now on: `{"name": "news-and-markets", "enabled": false, "enabledPacks": [...]}`; a refusal is a `400` with the
`DRS-5001` sentence above. See [API_GUIDE.md](API_GUIDE.md).

### Who sees which pack

Four lists decide what a user sees. Each one narrows the one before it:

| List | Set by | Meaning |
|---|---|---|
| **Loaded** | `DRISHTI_PACKS`, at start-up (and **Load**) | what the server runs (the listed packs and their parents) |
| **Switched on** | an administrator, for everyone (*Admin → Packs*) | which loaded packs are in use; all of them unless switched off ([above](#switching-packs-off-and-on-admin--packs)) |
| **Assigned** | an administrator, per user (*Admin → Users*) | what this user may use. A user who was never given a list of packs (for example one created through the API without `packs`) gets `drishti.packs.default-for-users` (`DRISHTI_DEFAULT_PACKS`; empty means every switched-on pack). A user given an **empty list** (every pack unticked in the dialog, or `"packs": []`) sees no pack: every kind answers `DRS-5002` no access. |
| **Active** | the user, with the pack menu in the top bar | which of their assigned packs they want to see now |

Example: the server loads the seven packs; an admin switches `news-and-markets` off; Priya is assigned
`trading`, `counterparty-risk` and `news-and-markets`; she ticks only `counterparty-risk`. Priya sees
counterparty risk, plus the kinds of the packs it extends (`trading`, `market-data`, `core`), and
nothing of news and markets even though it is assigned to her, because it is off for everyone.

**To assign packs to a user** (administrators), step by step:

1. Open *Admin → Users* (`/admin/users`) and click **Edit** on the user (or **New user**).
2. Under **Packs**, tick the packs this person may use, say `core` and `counterparty-risk`.
   Ticking none means *default packs* (`DRISHTI_DEFAULT_PACKS`, or every switched-on pack when that is empty).
3. Click **Save**. The user list now shows `core, counterparty-risk` in the user's row (or
   *default packs*), and the change is in the audit log.
4. At the user's next click, their pack menu lists only those two packs, and kinds of other packs answer
   `DRS-5002`.

The same with the API (an admin's token when security is on), when creating a user:

```bash
curl -s -X POST localhost:18480/api/v1/admin/users -H "Authorization: Bearer $DRISHTI_TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"priya","displayName":"Priya Raman","desk":"Credit","roles":["viewer"],
       "packs":["core","counterparty-risk"],"password":"credit-desk-2026"}'
```

`PUT /api/v1/admin/users/priya` with a `packs` list changes it later. See
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md) for the user dialog, roles, and where assignments are stored
(`drishti_user_pack`).

**To choose which packs you see** (any user with more than one pack): click the round box tool in the top bar
(it shows how many packs you have on), tick the packs (**all** and **none** help), and click **Apply**. The choice is saved to your
account. (For programs: `GET /api/v1/me/packs` answers `{"assigned": [...], "active": [...]}` for the signed-in user, and `PUT` with
`{"active": [...]}` chooses.)

**Enforcement is on the server.** A kind owned by a pack that is not active for you cannot be opened: the view
answers `DRS-5002` (forbidden). Its mnemonics, suggestions, examples, starter workspaces, guides and alert
suggestions disappear too. Kinds no pack owns are not affected. A user who may see
`market-risk` may also open the kinds of the packs it extends (a VaR result links to its trades and books).

Roles work on top of packs: a role lists the kinds it may open (see [Roles and field masks](#roles-and-field-masks)).

### Loading a pack while the server runs

Admin → Packs → **Load** brings in a pack that is on disk but not loaded, without anyone touching the machine and **without a restart**
(a pack from a signed registry is installed the same way: [the registry](#a-signed-pack-registry-publishing-and-installing)).
Every way a pack changes while the server runs (Load, Unload, a deploy, a registry install or rollback, and an edit in the packs folder, below)
takes the same path, so they all behave alike:

1. The server runs the same check it runs at start-up on the loaded packs plus the new one (inheritance, clashes, the
   manifest, the YAML files the pack points at). A pack that would not load is refused with the reason, and nothing changes.
2. For Load, it records the pack in the **pack overlay**, `<data>/packs/added.yaml` (`DRISHTI_PACKS_OVERLAY`), a small
   configuration file the server imports at every start, so the pack is loaded after a restart too:

   ```yaml
   # Packs loaded by an administrator from Admin → Packs. Written by the Drishti server: change them there.
   # Read at every start, together with drishti.packs.enabled.
   drishti:
     packs:
       added:
         - helpdesk
   ```

3. It puts the pack to use **now**: its kinds and owners, its mnemonics (and so the command line, type-ahead and menus), its Sutras
   (re-read, listeners told what changed) and its connectors. A connector template becomes a connector file by the usual rule (written
   once if no file of that name exists, never overwritten afterwards; [Packs and connectors](#packs-and-connectors-where-a-pack-reads-its-data))
   and the connector starts. The process, its sessions and every open live view carry on.
4. It puts the settings a pack contributes to use **now** too: the roles it defines (a signed-in user's kinds follow at the next call),
   the link graph (reference patterns, fields, badges, impact), search columns and pivots, forms, formats, semantic hints, About pages and
   guides, kind routes and the sample-data folders of the demo connector. Each of these is built first from the new packs: if one cannot
   be used (a badge that does not compile, a format file that is not YAML, a pivot that does not read) the whole change is refused with
   the reason and nothing is swapped, so the last good packs keep running. When all are ready they are swapped in one after another
   inside the one reload that runs at a time, and the cached view layouts are dropped last: a request sees the old settings or the new
   ones, never a mixture. Nothing needs a restart (`restartFor` in the answer is always empty).

**Unload** does the same in reverse for a pack loaded here; packs named in `DRISHTI_PACKS` stay. A pack another loaded pack extends cannot be
unloaded: unload the dependants first. Unloading stops the connectors only that pack used (their files stay on disk), and a view someone
has open for one of the pack's kinds says **pack removed** (`DRS-1002`), not an error. Both are audited (`pack-loaded`,
`pack-unloaded`). The API: `POST /api/v1/admin/packs/{name}/load` and `…/unload`; the answer lists what was `loaded`, `reloaded` and `unloaded`,
any `problems` by pack, and `restartFor` (kept for compatibility, always empty); `"restarting"` is always `false`.

#### Live reload: the packs folder is watched

The server watches `drishti.packs.dir` (and `drishti.packs.installed-dir`) the way it watches Sutras and connector files:
`drishti.packs.watch` is `auto` (file events, with a poll every `drishti.packs.poll`, 5 s, as the safety net), `poll`, or `off` (only Admin → Packs
acts). A burst of changes (a copy in progress) is acted on once the folder has been quiet for `drishti.packs.reload-debounce` (750 ms). One reload
runs at a time. (Copying the help-desk pack into `config/packs/` of a running server loads it within about ten seconds:
`GET /api/v1/packs` then lists `helpdesk 0.1.0`, and the pack is added to the overlay.)

| What you do in the folder | What happens, with no restart |
|---|---|
| **Add** a pack folder (one with a `pack.yaml`) | Checked together with the loaded packs, then loaded and remembered in the overlay. A folder that does not check out is not loaded; Admin → Packs and Health say why, and it is tried again when you change it **and** by itself (below). |
| **Edit** `pack.yaml`, `about.yaml`, a Sutra or a sample | Checked again and swapped in: the new mnemonics, kinds, Sutras and connector templates apply; a mnemonic you renamed stops working under its old code. A template change does not rewrite a connector file that already exists (use *Reset to pack default*, [below](#packs-and-connectors-where-a-pack-reads-its-data)). |
| **Make a bad edit** (invalid YAML, a kind another pack owns, a missing parent) | **The last good version keeps running.** The problem is shown on the pack's row in Admin → Packs and on the Health page (status DEGRADED), and in the audit log (`pack-problem`). Fix the file and the problem clears. |
| **Delete** a pack folder | The pack is unloaded; connectors only it used stop; open views of its kinds say *pack removed*. If another loaded pack extends it, it is **not** unloaded: the problem says which pack needs it, and it stays as last loaded until that one goes. If `DRISHTI_PACKS` still names a deleted pack the server will not start until that is changed, and the problem says so. |

**Taking a pack out safely.** Deleting the folder works, but **Admin → Artifacts** (and the *Uninstall…* button on each pack in Admin → Packs) first lists what depends on
the pack (packs that extend it, connectors only it uses, BI datasets and reports over its kinds, monitors, alert rules, subscriptions, workspaces, shares, embed hosts),
refuses while anything does and offers *Disable instead*, and otherwise moves the folder to `<data>/uninstalled/` where it can be restored for 30 days. See
[OPERATIONALISING.md section 18](OPERATIONALISING.md#18-admin--artifacts-install-and-uninstall-safely).

#### A pack that was refused is tried again by itself

A pack can be refused (a clash over a kind or mnemonic with another pack, an `extends` that is not loaded, a bad file, a setting that
cannot be used) for a reason that later goes away without anyone touching the pack's own folder: the pack it clashed with is
unloaded or deleted, the pack it extends is added, the sample-pack switch changes, a connector file appears. So the server does not wait for the
folder to change. Every pack that is refused, or kept at its last good version, is listed as **failing** and checked again

- as soon as something it may depend on changes (the packs folder, the connector files, the sample-pack switch, the overlay), and
- on a schedule with exponential backoff: after `drishti.retry.packs.initial` (5 s), then twice as long each time, never longer than
  `drishti.retry.packs.max` (5 min), with jitter. An error that retrying cannot fix (the same refusal about a missing setting or
  refused credentials three times in a row, `permanent-after`) is looked at only every `max`.

Admin → Packs and Health show it on the pack's row: *failed — next retry in 20 s (attempt 3)* and the last error. **Retry now** (Admin
→ Packs, or `POST /api/v1/admin/retry/packs`; admin role, audited as `pack-retry-now`; an API token needs the `packs:admin` scope) checks at
once. When a pack loads because its blocker went away, the audit log has `pack-loaded` (actor `retry`) and `pack-recovered`; failures are
audited as `pack-retry-failed`, only on attempts 1, 2, 4, 8, … so a pack that stays broken for a day does not fill the log. The settings
are in [CONFIGURATION.md](../admin/CONFIGURATION.md#retry-settings) and the whole behaviour in
[OPERATIONS.md](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself). With `drishti.packs.watch: off` nothing looks at the
folder on a timer, and so nothing retries either: use Retry now.

Each change is audited (`pack-loaded`, `pack-reloaded`, `pack-unloaded`, `pack-problem`). Connectors that only unloaded packs used stay held back until
a loaded pack names them again; after a restart they follow the usual rule (a connector file runs).

### Sample packs

A pack that exists to demonstrate Drishti is marked `sample: true` in its `pack.yaml`. The packs that ship marked so are **compute-demo** (synthetic P&L strips and their sum computed by reactive connectors; not loaded by
default: [tutorial 23](../tutorials/23-computed-panels.md)) and **news-and-markets** (a small list of well-known topics and listed companies, with
news and quotes that [connectors compute on demand](../connectors/CONNECTOR_DEVELOPER_GUIDE.md#connectors-that-compute-data-on-demand) from Google News and Yahoo Finance; their
connectors are off as shipped, the Yahoo endpoint is unofficial and restricted, and names and tickers leave the network when they are on: internal demonstrations only); the retired example packs **genomics** and **logistics** (invented data in domains far from capital
markets) are samples too; **world-economy** is not a sample: it carries real public data ([REAL_DATA](DATA_GUIDE.md#the-world-economy-data-in-detail)); the banking, markets and risk packs
are the reference packs real ones build on, and are not samples. Who sees sample packs is the setting `drishti.packs.samples`
(`DRISHTI_PACKS_SAMPLES`):

| Mode | Who sees sample packs |
|---|---|
| `visible` (default) | Everyone with the pack assigned, as any pack. |
| `developers` | Only users with the **author** or **admin** power. For everyone else the server filters them exactly as a pack an administrator switched off: their kinds cannot be opened, their mnemonics, suggestions, menus, search results, About text, examples and the Build examples that point at their records disappear, and `GET /api/v1/packs` does not list them. |
| `hidden` | Nobody. Sample packs are not loaded (unless a loaded pack extends one), and the connectors only they use do not start. |

Once your own packs exist, set `developers`: your people see their domain, and your authors still have the samples to learn from.

**From Admin → Packs.** Sample packs carry a **Sample** badge. A *Sample packs* section shows the mode in force and offers **Hide samples from business
users** (sets `developers`), *Show samples to everyone* and *Hide samples from everyone*. The choice is saved on the server (`<data>/packs/samples-mode`,
`DRISHTI_PACKS_SAMPLES_FILE`), **overrides** the property until you press *Use the configuration*, is applied at once (`hidden` unloads the sample packs
and `visible` brings them back, with no restart) and is audited (`pack-samples`). The API: `GET` and `PUT /api/v1/admin/packs/samples` with
`{"mode": "visible" | "developers" | "hidden" | "default"}`.

**Sample documents are not sample packs.** Every pack has `samples/` (one `samples/<kind>.jsonl` per kind, listed in `samples/catalog.jsonl`),
served by the built-in demo source when `DRISHTI_DEMO_ENABLED` is `true` (the default). The run configuration **Drishti server (demo lakes)**
turns the demo source off, so every page reads the lakes; with it on, a sample answers before the lake for an id both hold.

### Packs and connectors: where a pack reads its data

A pack says **what** it reads; the site says **where**. In `pack.yaml` a pack lists the **names** of the connectors it reads through and routes each of its kinds to one.
From `config/packs/counterparty-risk/pack.yaml`:

```yaml
connectors:                     # a mapping: the names this pack reads through, each with the pack's template
  legal-store: { plugin: delta, kinds: [agreement, csa], settings: { root: "${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}", domain: legal } }
  credit-store: { plugin: delta, kinds: [netting-set, credit-limit, cva, sa-ccr], settings: { … domain: credit } }
  collateral-store: { plugin: delta, kinds: [collateral-balance, margin-call, simm], settings: { … domain: collateral } }
routes:                         # which connector answers each kind
  agreement: legal-store
  netting-set: credit-store
  margin-call: collateral-store
  # … one line per kind
```

The connector itself is a **site resource**: one YAML file per connector in `config/connectors/` (`drishti.sources.connectors-dir`, `DRISHTI_CONNECTORS_DIR`), and
the file name is the connector's name: `config/connectors/credit-store.yaml` is the connector `credit-store`. The file holds the plugin (a Delta lake, JSON Lines
files, a database, Kafka...), the kinds it serves and its settings. It is edited by the administrator, in an editor or in **Admin → Connectors**, and applied to
the running server without a restart; only that connector restarts. The model, the file format and an example for every store are in
[CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md).

- **Templates and what gets generated.** A pack may carry a suggestion for each connector it names: the `connectors:` **mapping** of definitions that every pack
  shipped with Drishti uses (read as the pack's templates), or `connector-templates:` beside a plain list of names. At the first start, for each connector a pack
  names, the server writes `config/connectors/<name>.yaml` from the template **if no file of that name exists**, and never overwrites an edited file afterwards (it is logged and
  audited as `connector-generated`). The file starts with the line `# Generated at first start from the connector template of pack '<pack>'. Edit it freely: an edited file is
  never overwritten; an unedited one follows the pack's template at an upgrade.`, then a stamp line (`# drishti-template: …`, the template's fingerprint and the
  text's hash), and the server keeps a marker of what it generated in `<data>/connectors/generated/<name>.properties`. So a shipped pack works out of the box.
  A file nobody edited follows the pack: a new version of the template replaces it (the file before kept beside it). Once edited, the **file is the site's**: it
  replaces the template (its values are never overwritten), and an upgrade only adds the keys the template gained, at any depth
  ([CONNECTOR_FILES.md, Upgrades](../connectors/CONNECTOR_FILES.md#upgrades-295)).
- **Back to the pack's template.** *Reset to pack default* (Admin → Connectors, or `drishti.py connector reset <name>`) rewrites the file from the pack's current template,
  keeping the earlier text in `<data>/connectors/history/<name>.<timestamp>.yaml`:

  ```text
  $ drishti.py connector reset helpdesk-store --server http://localhost:18480
  helpdesk-store is back to the pack's default (running)
  ```

  The file then starts `# The default suggested by pack 'helpdesk'.` A deploy of a new pack version also refreshes a generated file nobody edited when the
  pack's kinds changed; an edited one is flagged with *Reset to pack default*.
- **Kinds go through routes.** The pack's `routes:` say which connector answers each kind (then the default route, then any other connector serving the kind);
  a connector file's `kinds:`, when set, is the exact list the connector serves (it adds and narrows; the rule is in [CONNECTOR_FILES.md, How packs and files fit together](../connectors/CONNECTOR_FILES.md#6-how-packs-and-files-fit-together)). The same connector can therefore serve several packs while each pack stays in charge of its own kinds.
- **Several connectors for one kind.** Connectors with `union: true` that serve the same kind are read together, each holding its own part: the trading pack's three
  source systems ([Trading](#trading)).
- **Shared connectors.** Several packs may name the same connector. Admin → Connectors shows which packs use each one and for which kinds, and asks before you disable or delete a connector that
  packs use. A connector that a pack's template suggests is not deleted (it would come back): reset it to the pack's default, or disable it.
- **A connector nobody defined.** The pack still loads. Its kinds answer `DRS-1011` (connector not configured), Admin → Health shows the pack as degraded with
  `connectorsNotConfigured`, and Admin → Packs → **Data source** marks the connector *not configured* with a **Create** link that opens a new connector pre-filled from the
  pack's template.
- **Archives carry names and templates only.** A pack archive (`pack make`, `pack bundle`, Deploy archive) never contains a site's connection settings. Deploying
  one shows the connectors it names that this site lacks, and a **Create connectors** step writes their files from the templates.
- **Credentials** in a connector file are only `${ENV_VAR}` or `file:/path` references, never a literal.

![Admin -> Packs -> Data source: the pack's connectors, one not configured, with Edit and Create links](../connectors/img/connector_files/07-pack-connectors.jpg)

The precedence for a connector's settings, highest first: environment variables and `drishti.sources.connectors.*` in the server's own configuration (deprecated; they
override a file setting by setting), the connector file, the pack's template. `drishti.py connector list|get|apply|test|reset|...`
([CLI_GUIDE.md](CLI_GUIDE.md#connector-list-get-apply-delete-test-plugins-enable-disable-reset-history)) does from a terminal what Admin → Connectors does.

### History: an entity over time

Type a range after an id: `TRD 1000015 30` (the last 30 business days with data), `EQ EQ-CSCA 3M`, `FX FX-AUDUSD YTD`,
`TRD 1000015 FROM 2026-09-01 TO 2026-09-30`. Every kind a dated store holds has a history; a pack's `history:` block only chooses its
columns, parts and chart ([history: a kind over time](#history-a-kind-over-time)). The shipped packs declare:

| Pack | Kinds | Shape |
|---|---|---|
| trading | `trade` (key columns, `LEGS` and `EVENTS` as parts, `SOURCE_UPDATE_TIME` and `INPUT_DATE` changes ignored); `counterparty-trades`, `portfolio-trades`, `legal-entity-trades`, `index-trades` off (derived pages) | document |
| market-data | `equity` (`PRICE`), `equity-index` (`LEVEL`), `fx-spot` (`MID`), `ir-curve` (`TEN_Y`, `SLOPE_2S_10S`), `credit-curve` (`SPREAD_5Y`, `PD_1Y`), `commodity` (`FRONT`), `bond` (`PRICE`, `YIELD`), `inflation-index` (`LATEST`, `YOY`); equity, index, FX and bond fall back to the `HISTORY` list inside the document | market (a chart, high, low, change) |
| counterparty-risk | `netting-set`, `credit-limit`, `cva`: their headline measures as columns | document |
| market-risk | `var`, `trade-valuation`, `pnl-explain`: their measures as columns | document |

### BI over the packs' kinds

A pack's kinds are BI data with nothing to build: every kind of every pack you may open is a **dataset** named `pack.<pack>.<kind>`
(`pack.trading.trade`, `pack.core.counterparty`), certified by its pack, read-only, versioned with the pack, its columns, keys, meanings and
masks taken from the pack ([BI_GUIDE.md](BI_GUIDE.md), *Models in the rail*). Every BI model is rooted in packs: each of its datasets traces
back to pack kinds and their connectors ([BI_DESIGN.md section 14.3](../architecture/BI_DESIGN.md#143-a-model-is-a-mini-database-always-rooted-in-packs)).

- **What ships.** `config/bi/` holds the BI samples over the seven packs (nine models such as `trades-basic`, `trading-risk`,
  `trading-cashflows`, `counterparty-exposure`, `world-economics`, their datasets, reports, boards, a notebook and Python items). A test,
  `ShippedExamplesPacksTest`, checks that every one of them reads only what the seven packs serve (a kind of a shipped pack, a connector a
  shipped pack defines, a `pack.<pack>.<kind>` that exists), so a renamed kind or a retired pack cannot leave a broken BI sample behind.
- **BI inside a pack archive.** A pack may carry its own BI items in `config/bi/` inside the pack folder; deploying the pack imports them
  as a bundle into the shared workspace `packs`, with the pack as their audience ([BI_GUIDE.md](BI_GUIDE.md#bi-content-in-a-pack-archive)).
- **Before an uninstall**, Admin → Artifacts lists the BI datasets and reports over the pack's kinds ([OPERATIONALISING.md section 18](OPERATIONALISING.md#18-admin--artifacts-install-and-uninstall-safely)).

### Deploying an archive, history, and the pack's connectors (Admin → Packs)

Three more things live on **Admin → Packs**; each is written up step by step, with pictures, in
[OPERATIONALISING.md, section 17](OPERATIONALISING.md#17-deploy-from-admin--packs-connectors-history-and-roll-back).

- **Deploy an archive.** Upload a `.tar.gz` made by `drishti.py pack bundle` or `pack make` (the pack only, never data). The server checks the
  checksums and manifest, that no path leaves the pack folder, the server version it needs, the Sutras (lint and tests), the packs it extends, and an
  optional signature by a trusted publisher; shows what it **changes** from the running version (breaking: kinds or mnemonics removed or renamed;
  selection; layout; change), and, once you confirm, swaps it into `drishti.packs.installed-dir` (`<data>/packs/installed`), keeps the version it replaces
  and puts the new version to use at once, with no restart. If the pack cannot be used, the old files are put back.
- **History and roll back.** Every deployment, rollback and reverted attempt is listed, with the kept versions (`drishti.packs.deploy.keep-versions`, 5)
  and a **Roll back to this** button; `shipped` goes back to the copy that ships with the server.
- **Data source.** Per loaded pack, a **read-only view** of the connectors it names: each one's state, plugin, the kinds this pack takes from it, and what defines it (a
  connector file, the pack's template, or nothing, in which case it is marked *not configured* with a **Create** link). **Edit** links go to Admin → Connectors,
  where settings are changed, tested (**Test connection** lists the newest business dates and row counts per kind) and saved ([above](#packs-and-connectors-where-a-pack-reads-its-data)).
  The earlier per-pack override file `<data>/packs/settings/<pack>.yaml` is moved into connector files once, at the first start after the upgrade.

The same from the terminal: `drishti.py server packs deploy|history|rollback|datasource get`
([CLI_GUIDE.md](CLI_GUIDE.md#server-packs-deploy-history-rollback-and-datasource)). A personal API token needs the `packs:admin` scope; connectors have their own commands, `drishti.py connector ...`.

#### One pack: its own page

Each pack title on **Admin → Packs** links to `/admin/packs/<name>` (the *Builds on*, *needed by* and *Connectors* cells link to the packs and connectors they name).

![One pack: status, kinds with mnemonics, Sutras, connectors, loads and history](../connectors/img/connector_files/12-pack-detail.jpg)

The page shows the title, version, code, description, the **Sample** badge, who changed the pack and when, and the status: **on**, **off**, **not loaded** or **refused**, with the
reason and the retry state (*next retry in Ns (attempt k)*, **Retry now**) when the server is trying again. Under it: what the pack **builds on** and what **needs** it; the
**kinds with their mnemonics**, a link that opens a record of each kind (where one exists) and one to `FORM <pack>` for the kind, with its form fields (declared in the `form:` section or
inferred); the **Sutras** with their versions and **Open in Build**; the **Sutra coverage** of a kind whose Sutras match on its fields (the trading pack's source system × product
type); the **connectors it names**, each linked to its page, a name nothing defines flagged **missing** with **Create**;
how many kinds carry About text and glossary terms; today's **data-load expectations** and the last load, linking to the data loads page; and the pack's **deploy history** with
**Roll back to this**. **Switch on/off**, **Load** and **Unload** are the list's buttons with the same rules. The server side is `GET /api/v1/admin/packs/{name}/detail` (administrators; kinds, mnemonics, forms, Sutras
whose files live in the pack, About counts), `GET /api/v1/packs/{name}/coverage?kind=<kind>`, beside the existing `GET /api/v1/admin/packs`, `/packs/{name}/datasource`,
`/loads/{name}` and `/packs/history?pack=`.

### Telling Drishti new data has landed

A pack can say which kinds its ETL is expected to land each business day, by when, and who is told, in a `loads:` section of its `pack.yaml`
(an administrator can override it from Admin → Packs → *Data loads*). The ETL announces each batch with one call (`POST /api/v1/packs/{pack}/loads`
or `drishti.py data landed`); Drishti refreshes, verifies, evaluates alert rules and notifies, and flags a batch that is late on Admin → Health.
`drishti.py lake build --signal URL` and `lake demo` make that call for every business date they write. The manifest key, with examples:
[DATA_GUIDE.md](DATA_GUIDE.md#6-configuration-reference).

### Pack codes

Every shipped pack has a short `code:`; typed alone on the command line (with `<GO>`) it opens the pack's overview:
every kind you may open, its mnemonic, how many the sources hold for the business date, an example and the key
fields. Each row's mnemonic opens that kind's pick list. Typing the pack's name (`market-data`) works too. Below the kinds
the page shows the pack's README (`config/packs/<pack>/README.md`): its summary, its sources and, folded, the *Data structures*
of the kinds you may open (the server leaves the others out, as it does for the overview). Administrators see the whole README
under Admin → Packs → the pack.

| Pack | Code | | Retired example pack | Code |
|---|---|---|---|---|
| core | `CORE` | | finance | `FIN` |
| market-data | `MKT` | | logistics | `LOGI` |
| trading | `TRDS` | | genomics | `GENO` |
| counterparty-risk | `CCR` | | retail-banking | `RTL` |
| market-risk | `MRSK` | | operational-risk | `OPR` |
| world-economy | `WRLD` | | climate-risk | `CLI` |
| news-and-markets | `NWMK` | | liquidity-risk | `LIQ` |
| us-equities | `USEQS` | | | |
| gleif | `GLEIF` | | | |

The codes of the merged packs changed: `BNK` (banking-core) is `CORE`, `ECO` and `POLS` are `WRLD`, `NEWSW` and `MKTW` are `NWMK`.

The same answer as JSON, here for the trading pack on the demo lakes:

```bash
curl -s localhost:18480/api/v1/packs/TRDS/overview -H "Authorization: Bearer $DRISHTI_TOKEN" | python3 -c '
import json,sys; d=json.load(sys.stdin); print(d["name"], d["code"], [(k["mnemonic"], k["count"], k["example"]) for k in d["kinds"]])'
```

```text
trading TRDS [('TRD', 15000, 'CLY-3000001'), ('CTRD', 24, 'CP-ALDERSHOT'), ('PTRD', 30, 'BOOK-COMM-1'), ('LTRD', 4, 'LE-FRA'), ('ITRD', 57, 'AONIA')]
```

A pack author sets the code with `code:` in `pack.yaml` (in a generated pack, in its generator:
[how the shipped packs are generated](#how-the-shipped-packs-are-generated)).

### Upgrading and removing a pack

**Upgrading a pack on a running installation**, step by step:

1. Read the pack's change notes (its guide, or the generator's commit). Look for renamed kinds, mnemonics, attributes or
   link fields: saved monitors, workspaces, alert rules and BI items refer to kinds, fields and ids, and a renamed one leaves
   them pointing at nothing. `drishti.py pack diff OLD NEW` lists what changed and marks kinds and mnemonics removed or renamed as **BREAKING**.
2. Replace the folder `config/packs/<name>/` with the new version (for a repository checkout: `git pull`), or deploy its archive from Admin → Packs.
3. If it adds packs it extends, nothing else is needed: parents load with their child.
4. No restart is needed: the server notices the new folder within the poll interval and checks it with the loaded packs (or press
   Admin → Packs → Retry now). Watch Admin → Packs and the log for the pack loader's refusals (a kind now owned by two packs, a clash
   with an unrelated pack); a refused pack keeps its last good version and is tried again by itself.
5. Check *Admin → Health*: the pack shows its new version, `sutraProblems` is empty, and its connectors are
   up and none is *not configured* (a new version may name a connector this site has no file for yet: create it in Admin → Connectors).
   Existing connector files are not changed by an upgrade. Check *Admin → Packs*: a pack switched off before the upgrade stays off (the switch is kept by name).
6. Open one of its example commands from `/t`.

**Removing a pack:** prefer *Uninstall…* (Admin → Packs or Admin → Artifacts), which checks what depends on it first. Deleting its folder (or Unload in Admin
→ Packs if it was loaded there) works too; if `DRISHTI_PACKS` names it, take it out and restart. Saved monitors, workspaces and alert rules that
refer to its kinds stay saved, but those entities cannot be opened until the pack returns. To hide a
pack without a restart, switch it off in *Admin → Packs* instead.

### What stays in the core

- The engine: sources, the view pipeline, Rachana and Rachana-EL, inference, the entity graph, live updates, identity, BI.
- Neutral formats (amounts, signed, percent, compact, dates) and neutral semantic roles (amount, value, count,
  rate, date, label).
- The `viewer`, `author`, `approver` and `admin` roles, and the powers a role can hold (`raw`, `author`, `approve`,
  `admin`, `calc`).
- Calc's runtime and its `drishti` module; packs only switch it on and ship snippets.
- The generic help: using the terminal, panel kinds, the Build workbench, workspaces, and every reference.

### What Admin → Packs shows about a pack

The list names every connector a pack uses (its `connectors:` list, its `connector-templates:` and the connectors its `routes:` send kinds to). **Changed** shows the
time of the last switch or, when the pack's files changed later (a deploy, an edit), that time with *files on disk*. A Sutra that does not parse in a pack's folder is
listed on the row and on the pack's page as `file:line:column DRS-2010 message` (the file relative to the pack's folder); the pack stays on, the Sutra is not in use.
A `pack.yaml` with a syntax error says `<pack>/pack.yaml has a syntax error at line L, column C: ...`; it is not retried until the file changes. A version must be
**X.Y.Z** (`1.4.0`, `2.0.0-rc.1`): the wizard, `drishti.py pack bundle|make` and the server's deploy check all say so. Redeploying the same version keeps no duplicate;
the running version is not offered as a rollback target. A pack named in `drishti.packs.enabled` (`DRISHTI_PACKS`) can only be disabled in Artifacts: the dialog says so
first. Switch off, Unload, Load and Deploy ask in the page's own dialog. The page notices changes on disk within 5 seconds and offers **Refresh**; on a phone each
pack is a card and the introduction folds behind *About this page*.

## Part VI. The funds and ETFs pack

The `funds` pack (2.9.2) puts the **200 largest US mutual funds and ETFs** by net assets on the terminal, with their holdings, from
SEC public data: what each fund holds and how much (Form N-PORT), who runs, holds and audits it (Form N-CEN), what it costs and how
it did (the prospectus risk/return summary). The funds link to the rest of Drishti: a holding opens its company (`USEQ`), a company
lists the funds that hold it (`HELD`), an index lists the funds that track it (`TRACK`), an LEI opens its GLEIF record (`LEI`).

This guide has three parts. **Part I** is how to use it: the FUND page and its tabs, the funds holding a company, the funds tracking an
index, the commands, the command line and the API, worked through on the committed data. **Part II** is the data contract of every
kind: every column with its type, key, source in the SEC's tables and an example. **Part III** is where the data comes from: each
source and its licence, how the 200 are chosen, the refresh script on Linux and Windows, the gzipped snapshot and offline use. The
hands-on companion is [tutorial 28](../tutorials/28-funds-and-etfs.md); the words (NAV, share class, look-through, active share, overlap)
are explained in [Knowledge: Companies, funds and ETFs](../knowledge/companies-funds-and-etfs.md).

All figures below are from the committed snapshot: N-PORT reports dated 2026-05-31 to 2026-07-31 (the SEC's 2026 Q3 data set), retrieved
on 2026-10-26.

### Contents

Part I · Using it

1. [Turning the pack on](#1-turning-the-pack-on)
2. [The FUND page: the fact sheet](#2-the-fund-page-the-fact-sheet)
3. [The Look-through holdings tab](#3-the-look-through-holdings-tab)
4. [The fund against its index](#4-the-fund-against-its-index)
5. [Held by funds: HELD and the company page](#5-held-by-funds-held-and-the-company-page)
6. [Funds tracking an index: TRACK and the index page](#6-funds-tracking-an-index-track-and-the-index-page)
7. [Commands, search, command line and API](#7-commands-search-command-line-and-api)

Part II · The data contracts

8. [fund](#8-fund)
9. [fund-holder](#9-fund-holder)
10. [index-funds](#10-index-funds)
11. [Validation and conventions](#11-validation-and-conventions)

Part III · Where the data comes from

12. [The sources and their licences](#12-the-sources-and-their-licences)
13. [How the 200 are chosen](#13-how-the-200-are-chosen)
14. [How the documents are built](#14-how-the-documents-are-built)
15. [The refresh script](#15-the-refresh-script)
16. [The gzipped snapshot and offline use](#16-the-gzipped-snapshot-and-offline-use)
17. [What is not included](#17-what-is-not-included)

## Part I · Using it

### 1. Turning the pack on

The pack is **off by default**, like `us-equities` and `gleif`, and needs `us-equities` (its holdings link to the companies there; with
`gleif` on, the LEIs open their records too). Load it and build its tables into the lake:

```bash
export DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,us-equities,gleif,funds
python3 tools/drishti.py lake demo                    # every table; or: python3 tools/build_demo_lakes.py --only funds
```

On Windows, `.\tools\windows\start-server.ps1 -DemoLakes` loads the public-data packs including `funds`, and
`python tools\build_demo_lakes.py --only funds` builds its three tables. The demo cluster (`drishti-stack/stack.yaml`) loads it on
every node. Type `FUNDS` and press Enter: the pack's overview lists its three commands with their counts (200 funds, 586 companies
held, 3 indexes tracked).

The pack's data is served by its Delta connector `funds-store` from the lake, never by the demo connector: a server with
`DRISHTI_DEMO_ENABLED=false` opens every page.

### 2. The FUND page: the fact sheet

`FUND QQQ` opens Invesco QQQ. The page is the **fact sheet** (the main view), with two tabs under it: **Look-through holdings** and
**vs Nasdaq-100** (section 3 and 4), the pattern of the exposure explorer.

![FUND QQQ: the fact sheet, its tiles, the ten largest holdings and the fund's facts](../tutorials/img/t28/02-fund-fact-sheet.jpg)

From top to bottom:

- **The strip**: ticker, type, the index it tracks, the date of its N-PORT report, its SEC series and CIK.
- **The tiles**: net assets (490.1bn USD on 2026-06-30), the expense ratio (0.18%, from the prospectus of 2025-12-19), holdings (105,
  equities 99.93%), the top 10's weight (45.00%), the last calendar-year return (+25.58% in 2024) and turnover (7.98%). A tile with no
  filed value (a unit investment trust files no prospectus summary) is left out, not shown empty.
- **Top 10 holdings** (F2): each holding links to its us-equities company (`USEQ NVDA`), or to another fund of the pack (a fund of
  funds); the line under it points to the other 95 in the Look-through tab.
- **The fund**: type, index (opens `IDX NDX`), adviser and fund LEI (open `LEI` when the gleif pack is on), registrant, every share
  class ticker, the exchange, the ETF's creation unit and authorised participants, its rank by net assets.
- **Holdings by SIC division** (F3): the us-equities companies held, by SIC division, in percent of net assets.
- **Calendar-year returns** (F4), **Flows and returns, three months** (sales, redemptions, net flow and the class's monthly return;
  totals) and **Service providers** (custodian, auditor, administrator, transfer agent, sub-advisers, the census period).

The types: **ETF**; **Mutual fund**; **Mutual fund with an ETF share class** (Vanguard's funds: `FUND VOO` is the Vanguard 500 Index
Fund, whose share classes are VOO, VFINX, VFIAX and VFFSX); **ETF (unit investment trust)** (SPY, DIA). The fund's ticker is its ETF
class where it has one, else its oldest class; `FUND VFIAX` opens VOO (section 7).

`FUND VOO` is the second-largest fund: 1,671.2bn of net assets, 520 holdings, expense ratio 0.03%, +17.84% in 2025, flows of +28.5bn,
+15.8bn and −8.5bn in April to June 2026, custodian State Street Bank and Trust Company, auditor PricewaterhouseCoopers.

### 3. The Look-through holdings tab

The first tab is the look-through: what the fund holds, all of it, and who it is exposed to.

![QQQ's Look-through tab: concentration, asset and issuer types, the derivative with its counterparty, every holding](../tutorials/img/t28/03-look-through.jpg)

- **Concentration**: top 10 and top 50 weights, the Herfindahl index (the sum of squared weights: 0.029 for QQQ), the largest holding,
  how many holdings carry an issuer LEI (101 of 105), the us-equities companies held and their weight, and how many holdings are shown.
- **By asset type** and **By issuer type**: N-PORT's asset categories (common equity, debt, repo, derivatives ...) and issuer types
  (corporate, US Treasury, municipal, registered fund ...).
- **Derivatives and counterparties**: the largest 50 positions by notional, each with its counterparty (an LEI opens GLEIF) and
  expiry. QQQ holds one: a NASDAQ-100 future, 327.3m notional, cleared at the Chicago Mercantile Exchange. A bond fund (`FUND PONAX`,
  the PIMCO Income Fund) shows the largest of its 1,730 swaps and forwards, with 38 counterparties.
- **Counterparties of derivatives and repos**: per counterparty, positions, notional and value.
- **Holdings**: every holding kept (a fund keeps its 600 largest by value, every S&P 500 fund whole; the line above says how many
  others there are and their weight), paged and filterable: issuer (a link to its company, fund or LEI), weight, value, asset category,
  ticker, ISIN, CUSIP, country, and **Also in**: how many other funds of the pack hold the same company (opens `HELD`).
- **Who else holds** the fund's largest us-equities company: the ten other funds holding it the most (QQQ: NVIDIA).

### 4. The fund against its index

The second tab compares the fund with the index it tracks; an equity fund that tracks none of the pack's indexes is compared with the
S&P 500 for reference (`FUND AGTHX`, the Growth Fund of America: active share 44%), and the tab's title says so.

![QQQ against the Nasdaq-100: coverage, active share, weights side by side, the tilt by SIC division](../tutorials/img/t28/04-versus-index.jpg)

- **Coverage**: the index, members held of those listed, the weight in the listed members and elsewhere, the **active share** (half
  the sum of the weight differences), the largest difference, how the index weights are made and which members are not weighted.
- **Weights** of the 25 largest companies in the fund and in the index, and the difference.
- **Tilt by SIC division**: fund, index and the difference in percentage points.

**Index weights are proprietary** to the index providers and not in public data. The tab computes them from SEC data: each member's
market capitalisation is its shares outstanding on its latest 10-K cover (us-equities `SHARES_OUTSTANDING`) times the price the funds'
N-PORT filings imply (value over shares, the median across the funds). A member whose filed share count the holdings cannot support
(none filed, a scale error, one class of several: the funds would own under 0.2% or over 80% of it) is left out and named, and both
sides are compared over the members weighted; with fewer than 70% weighted, equal weights are used and the tab says so. The
Nasdaq-100 caps its largest members (its published method: a member over 24% is cut to 20%, and members over 4.5% that weigh more than
48% together are cut to 40%), and the tab applies the same caps; the Dow Jones Industrial Average weighs its members by share price,
and so does the tab (`FUND DIA`: active share 1.8%). The members are the us-equities pack's: what the index's tracking ETF (SPY, QQQ,
DIA) reported holding in its newest N-PORT. An S&P 500 fund still shows an active share of about 5.3% rather than 0 (`FUND VOO`
holds 493 of the 495 members) because 41 members have no share count the filings support (Alphabet, Meta, Berkshire Hathaway among
them) and are left out of the weights; `FUND QQQ` shows 11.2% for the same reason (15 of its 99 members). Until 2.9.3 the members came
from Wikidata, whose lists were out of date (QQQ then showed 26.75%).

### 5. Held by funds: HELD and the company page

`HELD AAPL` lists every fund of the pack that holds Apple, by value, counting every holding (not only the 600 a fund page keeps):

![HELD AAPL: 85 funds hold Apple, 831.0bn together, 19.02% of its shares](../tutorials/img/t28/06-held-by-funds.jpg)

The strip: 85 funds (26 ETFs) hold 831.0bn of Apple, **19.02% of its shares outstanding**, at an average weight of 5.425% and at most
14.57% (a technology sector fund). The table: fund (opens it), type, Apple's weight in it, value, shares and the report date; on the
right, the ten largest weights. The share of shares outstanding is left empty for a company whose filed share count cannot be compared.

With the funds pack on, the company page `USEQ AAPL` has two tabs under its financials and filings: **Held by funds** (the same table)
and **Fund ownership** (the strip's figures, with a link to `HELD AAPL`). They read the funds pack's `fund-holder` record as their
source; with the pack off they say so.

![USEQ AAPL: the Held by funds tab under the company's financials](../tutorials/img/t28/07-useq-tab.jpg)

### 6. Funds tracking an index: TRACK and the index page

`TRACK SP500` lists the funds that track the S&P 500 by net assets: seven, four of them ETFs, 4,821.3bn together, with each one's
expense ratio, members held and active share against the computed index weights.

![TRACK SP500: the seven S&P 500 funds of the pack](../tutorials/img/t28/08-track-index.jpg)

A fund tracks an index when its name names it (`S&P 500`, `500 Index`, `Nasdaq-100`, `QQQ`, `Dow Jones Industrial`), without a style
or a screen (Growth, Value, Equal Weight, ESG ...); N-CEN says whether a fund is an index fund, but not which index. `TRACK NDX` has
QQQ, QQQM and TQQQ (leveraged, but tracking the Nasdaq-100); `TRACK DJIA` has DIA. The index page `IDX SP500` shows the same list on
the right, **ETFs and funds tracking it** (F4); the bank lists (`IDX BANKS-US` ...) have none.

### 7. Commands, search, command line and API

| Type | Opens | Examples |
|---|---|---|
| `FUNDS` | the pack's overview | `FUNDS` |
| `FUND <ticker>` | a fund: the fact sheet and its tabs; any share class ticker opens its fund | `FUND QQQ`, `FUND VTI`, `FUND VTSAX` (opens VTI), `FUND SPY`, `FUND AGG` |
| `FUND <field>=<value>` | a pick list of funds | `FUND INDEX_ID=SP500`, `FUND TYPE=ETF`, `FUND EXPENSE_RATIO<0.05`, `FUND NET_ASSETS>500bn` |
| `HELD <ticker>` | the funds holding a us-equities company | `HELD AAPL`, `HELD NVDA` |
| `TRACK <index>` | the funds tracking a us-equities index | `TRACK SP500`, `TRACK NDX`, `TRACK DJIA` |
| `FORM funds` | the pack's search form (ticker, name, share class tickers, type, adviser, index, net assets, expense ratio, holdings) | |

Typing `FUND vang` offers the Vanguard funds; `FUND VTSAX` offers VTI (the type-ahead matches every share class ticker, `TICKERS`), and
pressing Enter on it opens VTI. The function keys of the FUND page: F2 top holdings, F3 sectors, F4 returns, F5 the tabs, F6 the funds
holding the largest company, F7 the funds tracking the index, F9 the raw document.

![FUND INDEX_ID=SP500: eight funds whose name names the S&P 500](../tutorials/img/t28/05-search.jpg)

**Command line** (`DRISHTI_SERVER` and `DRISHTI_TOKEN` set, or `--user` with sign-in off):

```bash
python3 tools/drishti.py view get fund/QQQ              # the page as text: tiles, tables, tabs
python3 tools/drishti.py view get fund-holder/AAPL
python3 tools/drishti.py view get index-funds/SP500
python3 tools/contracts.py show FUND                    # the kind's contract
```

```text
$ drishti.py view get index-funds/SP500
Funds · Funds tracking an index  SP500  with S&P 500   [TRACK]

Key figures
  figure               value
  -------------------  ---------
  Index                S&P 500
  Funds                7
  of which ETFs        4
  Net assets together  4,821.3bn
  Largest              VOO

Funds tracking the S&P 500, by net assets (a fund opens its page)  (table, trackers, F2)
  Fund                                     Ticker  Type                                 Net assets  Expense ratio  Members held  Active share*
  Vanguard 500 Index Fund                  VOO     Mutual fund with an ETF share class   1,671.2bn         0.03 %           493         5.29 %
  iShares Core S&P 500 ETF                 IVV     ETF                                     888.1bn         0.03 %           493         5.29 %
  Fidelity 500 Index Fund                  FXAIX   Mutual fund                             832.2bn         0.01 %           487         5.41 %
  ...
```

**API**: `GET /api/v1/views/fund/QQQ` (the page), `GET /api/v1/entities/fund/QQQ/raw` (the document), `GET
/api/v1/search?q=FUND%20INDEX_ID%3DSP500` (a search), `GET /api/v1/command/suggest?q=FUND%20VTSAX` (the type-ahead), as the
[API guide](API_GUIDE.md) describes; every call takes the caller's token and the business date.

## Part II · The data contracts

The contracts are `config/packs/funds/contracts/<kind>.contract.yaml` (checked by `python3 tools/contracts.py check`, published as
[docs/contracts/packs/funds.md](../contracts/packs/funds.md)); each column's meaning is the pack's glossary,
`config/packs/funds/config/about.yaml`, written by `tools/packgen/funds/make.py` from `tools/packgen/funds/fundsglossary.py`. The tables
below list every column with its type, whether it is the key, its meaning (with its unit), its source and an example from the
snapshot. Source names are the SEC data sets' tables and columns: **N-PORT** `FUND_REPORTED_INFO`, `FUND_REPORTED_HOLDING`,
`IDENTIFIERS`, `MONTHLY_TOTAL_RETURN`, the derivative tables of part C.11; **N-CEN** `FUND_REPORTED_INFO`, `ETF`, `ADVISER`,
`CUSTODIAN`, `AUTHORIZED_PARTICIPANT` ...; the **risk/return summary**'s XBRL tags in `num.tsv`. A column of a list is written
`LIST.COLUMN`.

### 8. fund

One document per fund, keyed by `TICKER` (command `FUND`), 200 documents; stored as the Delta table `<root>/funds/fund/` with the
promoted columns `TICKER, NAME, TYPE, TICKERS, ADVISER, INDEX_ID, NET_ASSETS, EXPENSE_RATIO, HOLDING_COUNT, REPORT_DATE, RANK` (the
search form and the type-ahead read them; the type-ahead also `NAME` and `TICKERS`). Links: a holding's `COMPANY` to `USEQ`
(us-company), `FUND` to another `FUND`, `LEI`, `ADVISER_LEI`, `CUSTODIAN_LEI` and a counterparty's `CPTY_LEI` to `LEI` (gleif's
lei-entity), `INDEX_ID` to `IDX` (index-membership).

| Column | Type | Key | Meaning | Source (SEC table.column, or how it is made) | Links to | Example |
|---|---|---|---|---|---|---|
| `TICKER` | id | key | The fund's ticker: its ETF share class where it has one, else its oldest share class; a unit investment trust's listed ticker. | SEC company_tickers_mf.json, N-CEN SECURITY_EXCHANGE, the Nasdaq Trader directory, company_tickers_exchange.json |  | QQQ |
| `NAME` | text |  | The series name as filed (upper-case names title-cased). | N-PORT FUND_REPORTED_INFO.SERIES_NAME |  | Invesco QQQ Trust, Series 1 |
| `TYPE` | text |  | ETF, Mutual fund, Mutual fund with an ETF share class, or ETF (unit investment trust). | N-CEN FUND_REPORTED_INFO.IS_ETF, ETF.tsv, the Nasdaq Trader directory |  | ETF |
| `FUND_KEY` | text |  | The SEC series id, or CIK<cik> for a fund without one (a unit investment trust). | N-PORT FUND_REPORTED_INFO.SERIES_ID, REGISTRANT.CIK |  | S000101292 |
| `SERIES_ID` | id |  | The SEC series id (S and nine digits). | N-PORT FUND_REPORTED_INFO.SERIES_ID |  | S000101292 |
| `CIK` | text |  | The registrant's SEC Central Index Key (ten digits). | N-PORT REGISTRANT.CIK |  | 0001067839 |
| `REGISTRANT` | text |  | The trust or company the series belongs to. | N-PORT REGISTRANT.REGISTRANT_NAME |  | Invesco QQQ Trust, Series 1 |
| `LEI` | text |  | The fund's (series') LEI; opens its GLEIF record. | N-PORT FUND_REPORTED_INFO.SERIES_LEI | LEI (gleif) | 549300VY6FEJBCIMET58 |
| `REGISTRANT_LEI` | text |  | The registrant's LEI. | N-PORT REGISTRANT.LEI |  | 549300VY6FEJBCIMET58 |
| `TICKERS` | text |  | Every share class ticker, the fund's own first; the type-ahead matches any of them (FUND VTSAX opens VTI). | SEC company_tickers_mf.json |  | QQQ |
| `CLASSES` | record list |  | Each share class: id, ticker, name, expense ratio and three-month return. | company_tickers_mf.json, N-CEN SHARES_OUTSTANDING, risk/return summary, N-PORT MONTHLY_TOTAL_RETURN |  |  |
| `CLASSES.CLASS_ID` | id |  | The SEC class id (C and nine digits). | company_tickers_mf.json classId |  | C000271435 |
| `CLASSES.TICKER` | id |  | The share class's ticker. | company_tickers_mf.json symbol |  | QQQ |
| `CLASSES.CLASS_NAME` | text |  | The class's name (Admiral Shares, Class A ...). | N-CEN SHARES_OUTSTANDING.CLASS_NAME |  | Investor Shares |
| `CLASSES.EXPENSE_RATIO` | rate |  | The class's net expense ratio (gross where no net one is filed). (%) | risk/return summary NetExpensesOverAssets, ExpensesOverAssets |  | 0.18 |
| `CLASSES.RETURN_3M` | amount |  | The class's three monthly returns compounded. (%) | N-PORT MONTHLY_TOTAL_RETURN |  | 27.64 |
| `CLASS_COUNT` | integer |  | How many share classes the fund has. (classes) | company_tickers_mf.json |  | 1 |
| `PRIMARY_CLASS` | text |  | The class id of TICKER; its fees and returns are the page's. | company_tickers_mf.json |  | C000271435 |
| `IS_ETF` | boolean |  | True when the fund is, or has, an exchange-traded share class. | N-CEN IS_ETF, ETF.tsv, SECURITY_EXCHANGE; the Nasdaq Trader directory |  | True |
| `IS_INDEX` | boolean |  | True when the fund says it is an index fund, or its name names a tracked index. | N-CEN FUND_REPORTED_INFO.IS_INDEX |  | True |
| `IS_FUND_OF_FUNDS` | boolean |  | True when the fund invests in other funds. | N-CEN FUND_REPORTED_INFO.IS_FUND_OF_FUND |  | False |
| `IS_TARGET_DATE` | boolean |  | True for a target-date fund. | N-CEN FUND_REPORTED_INFO.IS_TARGET_DATE |  | False |
| `IS_MASTER_FEEDER` | boolean |  | True when the fund is part of a master-feeder structure. | N-CEN FUND_REPORTED_INFO.IS_MASTER_FEEDER |  | False |
| `INDEX_ID` | id |  | The us-equities index the fund tracks (SP500, NDX, DJIA), matched by its name; opens IDX. | computed from the name (N-CEN says whether a fund is an index fund, not which index) | IDX (index-membership) | NDX |
| `EXCHANGE` | text |  | Where the ETF share class lists. | the Nasdaq Trader directory; N-CEN SECURITY_EXCHANGE |  | Nasdaq |
| `CREATION_UNIT` | amount |  | Shares in one creation unit of the ETF. (shares) | N-CEN ETF.NUM_SHARES_PER_CREATION_UNIT |  | 100000.0 |
| `AP_COUNT` | integer |  | How many authorised participants the ETF reports. (firms) | N-CEN AUTHORIZED_PARTICIPANT |  | 40 |
| `AUTHORISED_PARTICIPANTS` | record list |  | The ten most active authorised participants by purchases and redemptions. | N-CEN AUTHORIZED_PARTICIPANT |  |  |
| `AUTHORISED_PARTICIPANTS.NAME` | text |  | The authorised participant. | N-CEN AUTHORIZED_PARTICIPANT.PARTICIPANT_NAME |  | Merrill Lynch Pro |
| `AUTHORISED_PARTICIPANTS.LEI` | text |  | Its LEI. | N-CEN AUTHORIZED_PARTICIPANT.PARTICIPANT_LEI | LEI (gleif) | 549300PMHS66E71I2D34 |
| `AUTHORISED_PARTICIPANTS.PURCHASES` | amount |  | Creation units it bought over the year, by value. (USD) | N-CEN AUTHORIZED_PARTICIPANT.PURCHASE_VALUE |  | 14638424000.0 |
| `AUTHORISED_PARTICIPANTS.REDEMPTIONS` | amount |  | Creation units it redeemed over the year, by value. (USD) | N-CEN AUTHORIZED_PARTICIPANT.REDEEM_VALUE |  | 6246681000.0 |
| `ADVISER` | text |  | The fund's investment adviser. | N-CEN ADVISER.ADVISER_NAME |  | The Vanguard Group, Inc. |
| `ADVISER_LEI` | text |  | The adviser's LEI; opens its GLEIF record. | N-CEN ADVISER.ADVISER_LEI | LEI (gleif) | 5493002789CX3L0CJP65 |
| `SUB_ADVISERS` | text |  | Up to six sub-advisers. | N-CEN ADVISER (sub-advisers) |  | Geode Capital Management, LLC |
| `CUSTODIAN` | text |  | The fund's custodian (not a sub-custodian). | N-CEN CUSTODIAN.CUSTODIAN_NAME |  | JPMorgan Chase Bank NA |
| `CUSTODIAN_LEI` | text |  | The custodian's LEI. | N-CEN CUSTODIAN.CUSTODIAN_LEI | LEI (gleif) | 7H6GLXDRUGQFU57RNE97 |
| `ADMINISTRATOR` | text |  | The fund's administrator (not a sub-administrator). | N-CEN ADMIN.ADMIN_NAME |  | THE VANGUARD GROUP, INC. |
| `TRANSFER_AGENT` | text |  | The fund's transfer agent. | N-CEN TRANSFER_AGENT.TRANSFERAGENT_NAME |  | The Vanguard Group, Inc. |
| `AUDITOR` | text |  | The registrant's independent public accountant. | N-CEN PUBLIC_ACCOUNTANT.PUB_ACCOUNTANT_NAME |  | PRICEWATERHOUSECOOPERS LLP |
| `AUDITOR_LEI` | text |  | The accountant's LEI. | N-CEN PUBLIC_ACCOUNTANT.PUB_ACCOUNTANT_LEI |  | 5493002GVO7EO8RNNS37 |
| `LENDS_SECURITIES` | boolean |  | True when the fund lent securities in the year. | N-CEN FUND_REPORTED_INFO.DID_LEND_SECURITIES |  | False |
| `NCEN_PERIOD` | date |  | The fiscal year end of the N-CEN report used. | N-CEN SUBMISSION.REPORT_ENDING_PERIOD |  | 2025-12-31 |
| `NCEN_ACCESSION` | text |  | The N-CEN filing used. | N-CEN SUBMISSION.ACCESSION_NUMBER |  | 0000036405-26-000103 |
| `GLEIF_RELATIONSHIPS` | record list |  | The fund's relationships in GLEIF: managed by, sub-fund of (its umbrella), feeder to. | GLEIF golden copy level 2 (rr.csv) |  |  |
| `GLEIF_RELATIONSHIPS.TYPE` | text |  | Managed by, Sub-fund of or Feeder to. | GLEIF rr.csv Relationship.RelationshipType |  | Managed by |
| `GLEIF_RELATIONSHIPS.LEI` | text |  | The manager's, umbrella's or master fund's LEI. | GLEIF rr.csv Relationship.EndNode.NodeID | LEI (gleif) | EJW8VXOT5RJ3PPMVEB49 |
| `GLEIF_RELATIONSHIPS.STATUS` | text |  | ACTIVE or INACTIVE. | GLEIF rr.csv Relationship.RelationshipStatus |  | ACTIVE |
| `RANK` | integer |  | The fund's rank by net assets among the funds with a ticker. | computed |  | 7 |
| `REPORT_DATE` | date |  | The date the N-PORT report is as of. | N-PORT SUBMISSION.REPORT_DATE |  | 2026-06-30 |
| `FILED` | date |  | The date the N-PORT report was filed. | N-PORT SUBMISSION.FILING_DATE |  | 2026-08-28 |
| `ACCESSION` | text |  | The N-PORT filing used. | N-PORT SUBMISSION.ACCESSION_NUMBER |  | 0001067839-26-000030 |
| `NPORT_URL` | text |  | The filing's folder on EDGAR. | computed from CIK and ACCESSION |  | https://www.sec.gov/Archives/edgar/da... |
| `TOTAL_ASSETS` | amount |  | Total assets. (USD) | N-PORT FUND_REPORTED_INFO.TOTAL_ASSETS |  | 492478331787.0 |
| `NET_ASSETS` | amount |  | Net assets: what the 200 are chosen by. (USD) | N-PORT FUND_REPORTED_INFO.NET_ASSETS |  | 490103179942.0 |
| `EXPENSE_RATIO` | rate |  | The net expense ratio of the fund's share class (gross where no net one is filed). (%) | risk/return summary NetExpensesOverAssets, ExpensesOverAssets |  | 0.18 |
| `GROSS_EXPENSE_RATIO` | rate |  | Total annual operating expenses before waivers. (%) | risk/return summary ExpensesOverAssets |  | 0.18 |
| `MANAGEMENT_FEE` | amount |  | The management fee. (%) | risk/return summary ManagementFeesOverAssets |  | 0.18 |
| `TURNOVER` | amount |  | Portfolio turnover in the last fiscal year. (%) | risk/return summary PortfolioTurnoverRate |  | 7.98 |
| `PROSPECTUS_FILED` | date |  | The date of the risk/return summary used. | risk/return summary sub.tsv filed |  | 2025-12-19 |
| `ANNUAL_RETURNS` | record list |  | Up to ten calendar-year total returns of the share class (the prospectus bar chart). | risk/return summary AnnualReturnPercent, AnnlRtrPct |  |  |
| `ANNUAL_RETURNS.YEAR` | text |  | The calendar year. | risk/return summary num.tsv ddate |  | 2015 |
| `ANNUAL_RETURNS.RETURN` | amount |  | The year's total return. (%) | risk/return summary AnnualReturnPercent, AnnlRtrPct |  | 9.53 |
| `RETURN_LAST_YEAR` | amount |  | The latest calendar-year return filed. (%) | risk/return summary AnnualReturnPercent, AnnlRtrPct |  | 25.58 |
| `RETURN_YEAR` | integer |  | The year of RETURN_LAST_YEAR. | risk/return summary |  | 2024 |
| `RETURN_3M` | amount |  | The share class's three monthly returns of the report compounded. (%) | N-PORT MONTHLY_TOTAL_RETURN |  | 27.64 |
| `FLOWS` | record list |  | Each of the report's three months: sales, reinvestments, redemptions, net flow and the class's return. | N-PORT FUND_REPORTED_INFO SALES_FLOW_MON1-3 ... |  |  |
| `FLOWS.MONTH` | text |  | The month (yyyy-mm), the third being the report's. | computed from REPORT_DATE |  | 2026-04 |
| `FLOWS.SALES` | amount |  | Shares sold (subscriptions), by value. (USD) | N-PORT FUND_REPORTED_INFO.SALES_FLOW_MONn |  | 56960297589.31 |
| `FLOWS.REINVESTMENT` | amount |  | Distributions reinvested. (USD) | N-PORT FUND_REPORTED_INFO.REINVESTMENT_FLOW_MONn |  | 0.0 |
| `FLOWS.REDEMPTIONS` | amount |  | Shares redeemed, by value. (USD) | N-PORT FUND_REPORTED_INFO.REDEMPTION_FLOW_MONn |  | 46510905488.86 |
| `FLOWS.NET_FLOW` | amount |  | Sales plus reinvestment less redemptions. (USD) | computed |  | 10449392100.45 |
| `FLOWS.RETURN` | amount |  | The share class's total return in the month. (%) | N-PORT MONTHLY_TOTAL_RETURN.MONTHLY_TOTAL_RETURNn |  | 15.656032 |
| `SALES` | amount |  | The three months' sales. (USD) | N-PORT SALES_FLOW_MON1-3 |  | 216564959011.07 |
| `REDEMPTIONS` | amount |  | The three months' redemptions. (USD) | N-PORT REDEMPTION_FLOW_MON1-3 |  | 205720139470.74 |
| `REINVESTMENT` | amount |  | The three months' reinvested distributions. (USD) | N-PORT REINVESTMENT_FLOW_MON1-3 |  | 0 |
| `NET_FLOW` | amount |  | Sales plus reinvestment less redemptions over the three months. (USD) | computed |  | 10844819540.33 |
| `HOLDING_COUNT` | integer |  | Every holding the fund reported. (holdings) | N-PORT FUND_REPORTED_HOLDING |  | 105 |
| `HOLDINGS_SHOWN` | integer |  | Holdings kept in HOLDINGS (the 600 largest by value). (holdings) | computed |  | 105 |
| `HOLDINGS_REST_COUNT` | integer |  | Holdings not kept. (holdings) | computed |  | 0 |
| `HOLDINGS_REST_PCT` | rate |  | The weight of the holdings not kept. (% of net assets) | computed |  | 0 |
| `HOLDINGS_REST_VALUE` | amount |  | The value of the holdings not kept. (USD) | computed |  | 0 |
| `TOP10_PCT` | rate |  | The weight of the ten largest holdings. (% of net assets) | computed |  | 45.0 |
| `TOP50_PCT` | rate |  | The weight of the fifty largest holdings. (% of net assets) | computed |  | 87.448 |
| `HHI` | amount |  | The sum of the squared weights (as fractions): 1 is one holding, near 0 is many small ones. | computed |  | 0.02927 |
| `LARGEST` | text |  | The largest holding by value. | computed | FUND | NVIDIA Corp. |
| `LARGEST_PCT` | rate |  | The largest holding's weight. (% of net assets) | computed |  | 7.596757 |
| `WITH_LEI` | integer |  | Holdings whose issuer LEI was filed. (holdings) | computed |  | 101 |
| `EQUITY_PCT` | rate |  | The weight of common equity (asset category EC). (% of net assets) | computed |  | 99.927 |
| `US_COMPANY_PCT` | rate |  | The weight of the holdings that are us-equities companies. (% of net assets) | computed |  | 91.196 |
| `US_COMPANIES` | integer |  | How many us-equities companies the fund holds. (companies) | computed |  | 79 |
| `BY_ASSET` | record list |  | The weight by N-PORT asset category. | computed |  |  |
| `BY_ISSUER_TYPE` | record list |  | The weight by issuer type. | computed |  |  |
| `BY_COUNTRY` | record list |  | The weight by country, the largest ten. | computed |  |  |
| `BY_SECTOR` | record list |  | The weight of the us-equities companies held, by SIC division. | computed |  |  |
| `DERIVATIVE_COUNT` | integer |  | How many derivatives the fund holds. (positions) | computed |  | 1 |
| `DERIVATIVES_NOTIONAL` | amount |  | The notionals added up, by size. (USD) | computed |  | 327348711.0 |
| `DERIVATIVES` | record list |  | The fifty largest derivative positions by notional, with their counterparties. | N-PORT part C.11 |  |  |
| `COUNTERPARTIES` | record list |  | The counterparties of the derivatives and repos, by notional: positions, notional, value. | N-PORT DERIVATIVE_COUNTERPARTY, REPURCHASE_COUNTERPARTY |  |  |
| `COUNTERPARTIES.NAME` | text |  | The counterparty. | N-PORT DERIVATIVE_COUNTERPARTY_NAME |  | Chicago Mercantile Exchange Inc. |
| `COUNTERPARTIES.LEI` | text |  | The counterparty's LEI; opens its GLEIF record. | N-PORT DERIVATIVE_COUNTERPARTY_LEI | LEI (gleif) | SNZ2OJLFK8MNNCLQOF39 |
| `COUNTERPARTIES.POSITIONS` | integer |  | Positions with the counterparty. (positions) | computed |  | 1 |
| `COUNTERPARTIES.NOTIONAL` | amount |  | Their notionals added up, by size. (USD) | computed |  | 327348711.0 |
| `COUNTERPARTIES.VALUE` | amount |  | Their values added up. (USD) | computed |  | 8409789.0 |
| `COUNTERPARTY_COUNT` | integer |  | How many counterparties. (counterparties) | computed |  | 1 |
| `HOLDINGS` | record list |  | The 600 largest holdings by value, each with its identifiers and links. | N-PORT FUND_REPORTED_HOLDING, IDENTIFIERS |  |  |
| `MOST_HELD_COMPANY` | id |  | The largest us-equities company the fund holds. | computed | USEQ | NVDA |
| `MOST_HELD_NAME` | text |  | That company's name. | us-equities |  | Nvidia |
| `ALSO_HELD_BY` | record list |  | The ten other funds holding that company the most, by value. | computed from fund-holder |  |  |
| `ACTIVE_SHARE` | rate |  | Half the sum of the differences between the fund's and the index's weights (VS_INDEX). (%) | computed |  | 11.21 |
| `VIEWS` | record list |  | The page's tabs: Look-through holdings, and the fund against its index when there is one. | computed |  |  |
| `VIEWS.VIEW` | text |  | LOOK_THROUGH or VS_INDEX. | computed |  | LOOK_THROUGH |
| `VIEWS.TITLE` | text |  | The tab's title. | computed |  | Look-through holdings |
| `VS_INDEX` | record |  | The fund against its tracked index (or the S&P 500 for reference); index weights computed from SEC data. | computed |  |  |
| `VS_INDEX.INDEX_ID` | id |  | The us-equities index. | us-equities index-membership | IDX (index-membership) | NDX |
| `VS_INDEX.INDEX_NAME` | text |  | Its name. | us-equities index-membership |  | Nasdaq-100 |
| `VS_INDEX.BASIS` | text |  | tracks (the fund tracks it) or reference (an equity fund compared with the S&P 500). | computed |  | tracks |
| `VS_INDEX.WEIGHTING` | text |  | market capitalisation computed from SEC data (capped as the Nasdaq-100 caps it), share price for the Dow, or equal weights. | computed |  | market capitalisation computed from S... |
| `VS_INDEX.MEMBERS` | integer |  | The index's members as the us-equities pack lists them. (companies) | us-equities index-membership (the holdings of SPY, QQQ and DIA) |  | 99 |
| `VS_INDEX.MEMBERS_WEIGHTED` | integer |  | Members with a market capitalisation the filings support. (companies) | computed |  | 84 |
| `VS_INDEX.MEMBERS_NOT_WEIGHTED` | text |  | Members left out of the weights, by ticker. | computed |  | ABNB, APP, BKNG, CRWD, CRWV, DASH, DD... |
| `VS_INDEX.MEMBERS_HELD` | amount |  | Listed members the fund holds. (companies) | computed |  | 98 |
| `VS_INDEX.FUND_IN_INDEX_PCT` | rate |  | The fund's weight in the listed members. (% of net assets) | computed |  | 99.2 |
| `VS_INDEX.NOT_IN_INDEX_PCT` | rate |  | The rest: other companies, cash, futures. (% of net assets) | computed |  | 0.8 |
| `VS_INDEX.ACTIVE_SHARE` | rate |  | Half the sum of the absolute weight differences over the members weighted. (%) | computed |  | 11.21 |
| `VS_INDEX.LARGEST_DIFF_COMPANY` | text |  | The company with the largest difference. | computed |  | ASML |
| `VS_INDEX.LARGEST_DIFF_NAME` | text |  | Its name. | us-equities |  | ASML Holding |
| `VS_INDEX.LARGEST_DIFF` | amount |  | Fund weight less index weight. (% points) | computed |  | -1.915 |
| `VS_INDEX.AS_OF` | date |  | The fund's report date. | N-PORT SUBMISSION.REPORT_DATE |  | 2026-06-30 |
| `VS_INDEX.ROWS` | record list |  | The 25 largest weights in either, side by side. | computed |  |  |
| `VS_INDEX.ROWS.COMPANY` | id |  | The us-equities company. | us-equities | USEQ (us-company) | AAPL |
| `VS_INDEX.ROWS.NAME` | text |  | Its name. | us-equities |  | Apple Inc. |
| `VS_INDEX.ROWS.SECTOR` | text |  | Its SIC division. | us-equities |  | Manufacturing |
| `VS_INDEX.ROWS.FUND_PCT` | rate |  | The company's weight in the fund over the members weighted. (%) | computed |  | 10.655 |
| `VS_INDEX.ROWS.INDEX_PCT` | rate |  | Its computed market-cap weight in the index. (%) | computed: SEC shares outstanding times the price N-PORT implies |  | 18.474 |
| `VS_INDEX.ROWS.DIFF` | amount |  | Fund less index. (% points) | computed |  | -7.819 |
| `VS_INDEX.SECTORS` | record list |  | Fund and index weights by SIC division. | computed |  |  |
| `VS_INDEX.SECTORS.SECTOR` | text |  | The division. | us-equities |  | Manufacturing |
| `VS_INDEX.SECTORS.FUND_PCT` | rate |  | The fund's weight in it. (%) | computed |  | 67.8 |
| `VS_INDEX.SECTORS.INDEX_PCT` | rate |  | The index's weight in it. (%) | computed |  | 61.38 |
| `VS_INDEX.SECTORS.TILT` | amount |  | Fund less index. (% points) | computed |  | 6.42 |
| `PROVENANCE.SOURCE` | text |  | Where the data comes from. | written by tools/packgen/funds/fundsbuild.py |  | U.S. Securities and Exchange Commissi... |
| `PROVENANCE.LICENCE` | text |  | The source's licence (public domain). | written by tools/packgen/funds/fundsbuild.py |  | Public domain (U.S. Government work);... |
| `PROVENANCE.RETRIEVED_AT` | date |  | The date the data sets were downloaded. | the download cache |  | 2026-10-26 |
| `PROVENANCE.NOTE` | text |  | What the document holds and from which filings. | written by tools/packgen/funds/fundsbuild.py |  | Holdings, flows and returns as the fu... |
| `HOLDINGS.NAME` | text |  | The issuer's name as the fund filed it. | N-PORT FUND_REPORTED_HOLDING.ISSUER_NAME |  | NVIDIA Corp. |
| `HOLDINGS.TITLE` | text |  | The title of the security or contract. | N-PORT FUND_REPORTED_HOLDING.ISSUER_TITLE |  | NVIDIA Corp. |
| `HOLDINGS.TICKER` | id |  | The security's ticker where the fund gave one, written as us-equities writes it (BRK/B is BRK-B). | N-PORT IDENTIFIERS.IDENTIFIER_TICKER |  | NVDA |
| `HOLDINGS.CUSIP` | text |  | The security's CUSIP (none for N/A or zeros). | N-PORT FUND_REPORTED_HOLDING.ISSUER_CUSIP |  | 67066G104 |
| `HOLDINGS.ISIN` | text |  | The security's ISIN where the fund gave one. | N-PORT IDENTIFIERS.IDENTIFIER_ISIN |  | US67066G1040 |
| `HOLDINGS.LEI` | text |  | The issuer's Legal Entity Identifier; opens its GLEIF record (gleif pack). | N-PORT FUND_REPORTED_HOLDING.ISSUER_LEI | LEI (gleif) | 549300S4KLFTLO7GSQ80 |
| `HOLDINGS.ASSET` | text |  | N-PORT's asset category: EC common equity, EP preferred, DBT debt, ABS-MBS, ABS-ASBS, ABS-CBDO, ABS-O, DCO, DCR, DE, DFE, DIR, DO derivatives, SN, LON, RA repo, STIV, RE, COMM, OTHER. | N-PORT FUND_REPORTED_HOLDING.ASSET_CAT |  | EC |
| `HOLDINGS.ISSUER_TYPE` | text |  | CORP, UST, USGA, USGSE, MUN, NUSS (non-US sovereign), PF (private fund), RF (registered fund), OTHER. | N-PORT FUND_REPORTED_HOLDING.ISSUER_TYPE |  | CORP |
| `HOLDINGS.COUNTRY` | text |  | The country of investment or issuer (ISO 3166). | N-PORT FUND_REPORTED_HOLDING.INVESTMENT_COUNTRY |  | US |
| `HOLDINGS.CURRENCY` | text |  | The currency the holding is denominated in. | N-PORT FUND_REPORTED_HOLDING.CURRENCY_CODE |  | USD |
| `HOLDINGS.VALUE` | amount |  | The holding's value as reported, in US dollars. (USD) | N-PORT FUND_REPORTED_HOLDING.CURRENCY_VALUE |  | 37231945639.46 |
| `HOLDINGS.PCT` | rate |  | The holding's value over the fund's net assets. (% of net assets) | N-PORT FUND_REPORTED_HOLDING.PERCENTAGE |  | 7.596757 |
| `HOLDINGS.BALANCE` | amount |  | Shares, principal amount or contracts held, in UNIT. | N-PORT FUND_REPORTED_HOLDING.BALANCE |  | 186075994.0 |
| `HOLDINGS.UNIT` | text |  | NS shares, PA principal amount, NC contracts, OU other units. | N-PORT FUND_REPORTED_HOLDING.UNIT |  | NS |
| `HOLDINGS.PAYOFF` | text |  | Long, Short or N/A. | N-PORT FUND_REPORTED_HOLDING.PAYOFF_PROFILE |  | Long |
| `HOLDINGS.DERIVATIVE` | text |  | FWD, FUT, SWP, OPT, SWO, WAR or OTH for a derivative; empty otherwise. | N-PORT FUND_REPORTED_HOLDING.DERIVATIVE_CAT |  | SWP |
| `HOLDINGS.CPTY` | text |  | The derivative's or repo's counterparty ('(cleared)' for a centrally cleared repo). | N-PORT DERIVATIVE_COUNTERPARTY, REPURCHASE_COUNTERPARTY |  | LCH Ltd. |
| `HOLDINGS.CPTY_LEI` | text |  | The counterparty's LEI; opens its GLEIF record. | N-PORT DERIVATIVE_COUNTERPARTY_LEI, REPURCHASE_COUNTERPARTY.LEI | LEI (gleif) | F226TOH6YD6XJB17KS62 |
| `HOLDINGS.NOTIONAL` | amount |  | A derivative's notional (a forward's amount bought), as filed. | N-PORT FUT_FWD_NONFOREIGNCUR_CONTRACT, NONFOREIGN_EXCHANGE_SWAP, FWD_FOREIGNCUR_CONTRACT_SWAP, SWAPTION_OPTION_WARNT_DERIV |  | 3516800000.0 |
| `HOLDINGS.EXPIRY` | date |  | A derivative's expiry, termination or settlement date; a repo's maturity. | N-PORT the derivative tables, REPURCHASE_AGREEMENT.MATURITY_DATE |  | 2051-06-16 |
| `HOLDINGS.UNREALIZED` | amount |  | A derivative's unrealised appreciation (negative: depreciation). (USD) | N-PORT the derivative tables, UNREALIZED_APPRECIATION |  | 1208405914.91 |
| `HOLDINGS.REFERENCE` | text |  | The index or basket a derivative references. | N-PORT DESC_REF_INDEX_BASKET.INDEX_NAME |  | S&P500 EMINI FUT Sep26 |
| `HOLDINGS.COMPANY` | id |  | The us-equities company the holding is (by ticker, else by the issuer's LEI for an equity); opens USEQ. | computed: the committed us-equities companies | USEQ (us-company) | NVDA |
| `HOLDINGS.FUND` | text |  | The pack's fund the holding is (a fund of funds holding another fund); opens FUND. | computed: the share class tickers | FUND | ABALX |
| `HOLDINGS.SECTOR` | text |  | The SIC division of the us-equities company. | us-equities us-company.SECTOR (SEC EDGAR) |  | Manufacturing |
| `HOLDINGS.ALSO_IN` | integer |  | How many other funds of the pack hold the same company. (funds) | computed from every fund's holdings |  | 86 |
| `DERIVATIVES.NAME` | text |  | The issuer's name as the fund filed it. | N-PORT FUND_REPORTED_HOLDING.ISSUER_NAME |  | CME E-Mini NASDAQ 100 Index Future |
| `DERIVATIVES.TITLE` | text |  | The title of the security or contract. | N-PORT FUND_REPORTED_HOLDING.ISSUER_TITLE |  | CME E-Mini NASDAQ 100 Index Future |
| `DERIVATIVES.CURRENCY` | text |  | The currency the holding is denominated in. | N-PORT FUND_REPORTED_HOLDING.CURRENCY_CODE |  | USD |
| `DERIVATIVES.VALUE` | amount |  | The holding's value as reported, in US dollars. (USD) | N-PORT FUND_REPORTED_HOLDING.CURRENCY_VALUE |  | 8409789.3 |
| `DERIVATIVES.PCT` | rate |  | The holding's value over the fund's net assets. (% of net assets) | N-PORT FUND_REPORTED_HOLDING.PERCENTAGE |  | 0.001716 |
| `DERIVATIVES.PAYOFF` | text |  | Long, Short or N/A. | N-PORT FUND_REPORTED_HOLDING.PAYOFF_PROFILE |  | N/A |
| `DERIVATIVES.DERIVATIVE` | text |  | FWD, FUT, SWP, OPT, SWO, WAR or OTH for a derivative; empty otherwise. | N-PORT FUND_REPORTED_HOLDING.DERIVATIVE_CAT |  | FUT |
| `DERIVATIVES.TYPE` | text |  | The derivative category in words (Future, Swap, Option ...). | computed from DERIVATIVE_CAT |  | Future |
| `DERIVATIVES.CPTY` | text |  | The derivative's or repo's counterparty ('(cleared)' for a centrally cleared repo). | N-PORT DERIVATIVE_COUNTERPARTY, REPURCHASE_COUNTERPARTY |  | Chicago Mercantile Exchange Inc. |
| `DERIVATIVES.CPTY_LEI` | text |  | The counterparty's LEI; opens its GLEIF record. | N-PORT DERIVATIVE_COUNTERPARTY_LEI, REPURCHASE_COUNTERPARTY.LEI | LEI (gleif) | SNZ2OJLFK8MNNCLQOF39 |
| `DERIVATIVES.NOTIONAL` | amount |  | A derivative's notional (a forward's amount bought), as filed. | N-PORT FUT_FWD_NONFOREIGNCUR_CONTRACT, NONFOREIGN_EXCHANGE_SWAP, FWD_FOREIGNCUR_CONTRACT_SWAP, SWAPTION_OPTION_WARNT_DERIV |  | 327348710.7 |
| `DERIVATIVES.EXPIRY` | date |  | A derivative's expiry, termination or settlement date; a repo's maturity. | N-PORT the derivative tables, REPURCHASE_AGREEMENT.MATURITY_DATE |  | 2026-09-18 |
| `DERIVATIVES.UNREALIZED` | amount |  | A derivative's unrealised appreciation (negative: depreciation). (USD) | N-PORT the derivative tables, UNREALIZED_APPRECIATION |  | 8409789.3 |
| `DERIVATIVES.REFERENCE` | text |  | The index or basket a derivative references. | N-PORT DESC_REF_INDEX_BASKET.INDEX_NAME |  | NASDAQ 100 Stock Index |
| `BY_ASSET.KEY` | text |  | The code grouped by (an asset category, issuer type, country or SIC division). | computed |  | EC |
| `BY_ASSET.LABEL` | text |  | The code in words; Other (n) gathers the smallest. | computed |  | Common equity |
| `BY_ASSET.PCT` | rate |  | The holdings' weight added up. (% of net assets) | computed from N-PORT PERCENTAGE |  | 99.927 |
| `BY_ASSET.VALUE` | amount |  | The holdings' value added up. (USD) | computed from N-PORT CURRENCY_VALUE |  | 489744804272.0 |
| `BY_ASSET.COUNT` | integer |  | The number of holdings in the group. (holdings) | computed |  | 102 |
| `BY_ISSUER_TYPE.KEY` | text |  | The code grouped by (an asset category, issuer type, country or SIC division). | computed |  | CORP |
| `BY_ISSUER_TYPE.LABEL` | text |  | The code in words; Other (n) gathers the smallest. | computed |  | Corporate |
| `BY_ISSUER_TYPE.PCT` | rate |  | The holdings' weight added up. (% of net assets) | computed from N-PORT PERCENTAGE |  | 99.927 |
| `BY_ISSUER_TYPE.VALUE` | amount |  | The holdings' value added up. (USD) | computed from N-PORT CURRENCY_VALUE |  | 489744804272.0 |
| `BY_ISSUER_TYPE.COUNT` | integer |  | The number of holdings in the group. (holdings) | computed |  | 102 |
| `BY_COUNTRY.KEY` | text |  | The code grouped by (an asset category, issuer type, country or SIC division). | computed |  | US |
| `BY_COUNTRY.LABEL` | text |  | The code in words; Other (n) gathers the smallest. | computed |  | US |
| `BY_COUNTRY.PCT` | rate |  | The holdings' weight added up. (% of net assets) | computed from N-PORT PERCENTAGE |  | 94.927 |
| `BY_COUNTRY.VALUE` | amount |  | The holdings' value added up. (USD) | computed from N-PORT CURRENCY_VALUE |  | 465238883928.0 |
| `BY_COUNTRY.COUNT` | integer |  | The number of holdings in the group. (holdings) | computed |  | 93 |
| `BY_SECTOR.KEY` | text |  | The code grouped by (an asset category, issuer type, country or SIC division). | computed |  | Manufacturing |
| `BY_SECTOR.LABEL` | text |  | The code in words; Other (n) gathers the smallest. | computed |  | Manufacturing |
| `BY_SECTOR.PCT` | rate |  | The holdings' weight added up. (% of net assets) | computed from N-PORT PERCENTAGE |  | 58.061 |
| `BY_SECTOR.VALUE` | amount |  | The holdings' value added up. (USD) | computed from N-PORT CURRENCY_VALUE |  | 284557152390.0 |
| `BY_SECTOR.COUNT` | integer |  | The number of holdings in the group. (holdings) | computed |  | 42 |
| `ALSO_HELD_BY.FUND` | text |  | The fund's ticker; opens FUND. | computed | FUND | VTI |
| `ALSO_HELD_BY.FUND_NAME` | text |  | The fund's name. | N-PORT FUND_REPORTED_INFO.SERIES_NAME |  | Vanguard Total Stock Market Index Fund |
| `ALSO_HELD_BY.TYPE` | text |  | The fund's type. | N-CEN FUND_REPORTED_INFO.IS_ETF, the Nasdaq Trader directory |  | Mutual fund with an ETF share class |
| `ALSO_HELD_BY.PCT` | rate |  | The company's weight in the fund (every line of it added). (% of net assets) | N-PORT FUND_REPORTED_HOLDING.PERCENTAGE |  | 6.3554 |
| `ALSO_HELD_BY.VALUE` | amount |  | The value the fund holds. (USD) | N-PORT FUND_REPORTED_HOLDING.CURRENCY_VALUE |  | 146036467451.0 |
| `ALSO_HELD_BY.SHARES` | amount |  | The shares the fund holds (balances in shares only). (shares) | N-PORT FUND_REPORTED_HOLDING.BALANCE |  | 729853903.0 |
| `ALSO_HELD_BY.REPORT_DATE` | date |  | The date the fund's report is as of. | N-PORT SUBMISSION.REPORT_DATE |  | 2026-06-30 |
| `ALSO_HELD_BY.NET_ASSETS` | amount |  | The fund's net assets. (USD) | N-PORT FUND_REPORTED_INFO.NET_ASSETS |  | 2297839156548.0 |

### 9. fund-holder

One document per us-equities company held by at least one fund, keyed by the company's `TICKER` (command `HELD`), 586 documents. Built
from every holding of every fund (not only those a fund page keeps): a holding is the company when its ticker is the company's, else
(an equity) when its CUSIP is one some fund filed with the company's ticker, its issuer LEI is the company's, or its issuer name is the
company's (legal suffixes and punctuation aside).

| Column | Type | Key | Meaning | Source (SEC table.column, or how it is made) | Links to | Example |
|---|---|---|---|---|---|---|
| `TICKER` | id | key | The us-equities company's ticker. | us-equities us-company.TICKER | USEQ, HELD | AAPL |
| `COMPANY` | id |  | The company; opens USEQ. | us-equities | USEQ (us-company) | AAPL |
| `NAME` | text |  | The company's name. | us-equities us-company.NAME |  | Apple Inc. |
| `SECTOR` | text |  | Its SIC division. | us-equities us-company.SECTOR |  | Manufacturing |
| `FUNDS` | integer |  | How many of the pack's funds hold it. (funds) | computed from every fund's holdings |  | 85 |
| `ETFS` | integer |  | How many of them are ETFs. (funds) | computed |  | 26 |
| `TOTAL_VALUE` | amount |  | The value the funds hold together. (USD) | computed from N-PORT CURRENCY_VALUE |  | 831040821744.0 |
| `TOTAL_SHARES` | amount |  | The shares the funds hold together. (shares) | computed from N-PORT BALANCE |  | 2809918580.0 |
| `SHARES_OUTSTANDING` | integer |  | The company's shares outstanding (10-K cover). (shares) | us-equities us-company.SHARES_OUTSTANDING (SEC XBRL dei) |  | 14776353000 |
| `PCT_OF_SHARES` | rate |  | Shares held over shares outstanding; empty where the filed count cannot be compared. (%) | computed |  | 19.016 |
| `AVG_WEIGHT` | rate |  | Its average weight in the funds holding it. (% of net assets) | computed |  | 5.425 |
| `MAX_WEIGHT` | rate |  | Its largest weight in a fund. (% of net assets) | computed |  | 14.5683 |
| `TOP_HOLDER` | text |  | The fund holding the most, by value. | computed | FUND | VTI |
| `HOLDERS` | record list |  | Every fund holding it, by value (at most 250). | computed |  |  |
| `TOP_WEIGHTS` | record list |  | The ten funds where it weighs the most. | computed |  |  |
| `TOP_WEIGHTS.FUND` | id |  | The fund's ticker. | computed | FUND | VGT |
| `TOP_WEIGHTS.PCT` | rate |  | The company's weight in it. (% of net assets) | computed |  | 14.5683 |
| `HOLDERS.FUND` | id |  | The fund's ticker; opens FUND. | computed | FUND | VTI |
| `HOLDERS.FUND_NAME` | text |  | The fund's name. | N-PORT FUND_REPORTED_INFO.SERIES_NAME |  | Vanguard Total Stock Market Index Fund |
| `HOLDERS.TYPE` | text |  | The fund's type. | N-CEN FUND_REPORTED_INFO.IS_ETF, the Nasdaq Trader directory |  | Mutual fund with an ETF share class |
| `HOLDERS.PCT` | rate |  | The company's weight in the fund (every line of it added). (% of net assets) | N-PORT FUND_REPORTED_HOLDING.PERCENTAGE |  | 5.8716 |
| `HOLDERS.VALUE` | amount |  | The value the fund holds. (USD) | N-PORT FUND_REPORTED_HOLDING.CURRENCY_VALUE |  | 134920536524.0 |
| `HOLDERS.SHARES` | amount |  | The shares the fund holds (balances in shares only). (shares) | N-PORT FUND_REPORTED_HOLDING.BALANCE |  | 466272244.0 |
| `HOLDERS.REPORT_DATE` | date |  | The date the fund's report is as of. | N-PORT SUBMISSION.REPORT_DATE |  | 2026-06-30 |
| `HOLDERS.NET_ASSETS` | amount |  | The fund's net assets. (USD) | N-PORT FUND_REPORTED_INFO.NET_ASSETS |  | 2297839156548.0 |
| `PROVENANCE.SOURCE` | text |  | Where the data comes from. | written by tools/packgen/funds/fundsbuild.py |  | U.S. Securities and Exchange Commissi... |
| `PROVENANCE.LICENCE` | text |  | The source's licence (public domain). | written by tools/packgen/funds/fundsbuild.py |  | Public domain (U.S. Government work);... |
| `PROVENANCE.RETRIEVED_AT` | date |  | The date the data sets were downloaded. | the download cache |  | 2026-10-26 |
| `PROVENANCE.NOTE` | text |  | What the document holds and from which filings. | written by tools/packgen/funds/fundsbuild.py |  | Every holding of the pack's funds in ... |

### 10. index-funds

One document per us-equities index that at least one fund tracks, keyed by `INDEX` (command `TRACK`): SP500, NDX, DJIA.

| Column | Type | Key | Meaning | Source (SEC table.column, or how it is made) | Links to | Example |
|---|---|---|---|---|---|---|
| `INDEX` | id | key | The us-equities index code (SP500, NDX, DJIA). | us-equities index-membership.INDEX | IDX | NDX |
| `NAME` | text |  | The index's name. | us-equities index-membership.NAME |  | Nasdaq-100 |
| `FUNDS` | integer |  | How many of the pack's funds track it. (funds) | computed |  | 2 |
| `ETFS` | integer |  | How many of them are ETFs. (funds) | computed |  | 2 |
| `TOTAL_NET_ASSETS` | amount |  | Their net assets added up. (USD) | computed from N-PORT NET_ASSETS |  | 587254661099.0 |
| `LARGEST` | text |  | The largest tracking fund. | computed | FUND | QQQ |
| `ROWS` | record list |  | The tracking funds by net assets. | computed |  |  |
| `ROWS.FUND` | id |  | The fund's ticker; opens FUND. | computed | FUND | QQQ |
| `ROWS.NAME` | text |  | Its name. | N-PORT SERIES_NAME |  | Invesco QQQ Trust, Series 1 |
| `ROWS.TYPE` | text |  | Its type. | N-CEN IS_ETF |  | ETF |
| `ROWS.IS_ETF` | boolean |  | True for an ETF. | N-CEN IS_ETF |  | True |
| `ROWS.NET_ASSETS` | amount |  | Its net assets. (USD) | N-PORT NET_ASSETS |  | 490103179942.0 |
| `ROWS.EXPENSE_RATIO` | rate |  | Its net expense ratio. (%) | risk/return summary NetExpensesOverAssets |  | 0.18 |
| `ROWS.MEMBERS_HELD` | amount |  | Listed members it holds. (companies) | computed |  | 98 |
| `ROWS.ACTIVE_SHARE` | rate |  | Against the computed index weights. (%) | computed |  | 11.21 |
| `ROWS.REPORT_DATE` | date |  | Its N-PORT report date. | N-PORT REPORT_DATE |  | 2026-06-30 |
| `PROVENANCE.SOURCE` | text |  | Where the data comes from. | written by tools/packgen/funds/fundsbuild.py |  | U.S. Securities and Exchange Commissi... |
| `PROVENANCE.LICENCE` | text |  | The source's licence (public domain). | written by tools/packgen/funds/fundsbuild.py |  | Public domain (U.S. Government work);... |
| `PROVENANCE.RETRIEVED_AT` | date |  | The date the data sets were downloaded. | the download cache |  | 2026-10-26 |
| `PROVENANCE.NOTE` | text |  | What the document holds and from which filings. | written by tools/packgen/funds/fundsbuild.py |  | The pack's funds whose name names thi... |

### 11. Validation and conventions

- **Units.** Amounts are US dollars as N-PORT reports them (`CURRENCY_VALUE`, net assets, flows); weights, ratios and returns are in
  percent as filed (a weight of 7.1 is 7.1% of net assets; an expense ratio of 0.03 is 0.03%). A holding's `BALANCE` is in its `UNIT`
  and a derivative's `NOTIONAL` in the currency filed; neither is converted.
- **Dates.** `REPORT_DATE` is the date the N-PORT report is as of; funds' dates differ (their fiscal quarters end in different
  months), so a comparison across funds compares reports a month or two apart. The snapshot is the same on every business day of the
  lake (`dated-by: snapshot`).
- **Checks.** `tools/contracts.py check` fails when a contract and its pack or data disagree; `tools/packgen/funds/make.py --check`
  fails when the committed snapshot, samples, README, glossary or pack guide differ from what the generator writes;
  `tools/testdata/test_testdata.py` fails when a committed data file is not gzipped or is over 5 MB.
- **Empty values.** An optional column is left out where the fund did not file it: N-CEN facts for a fund whose census is not among
  the four quarters read (7 of 200, among them QQQ, newly reorganised), fees and calendar-year returns for a fund without a
  risk/return summary (the unit investment trusts SPY and DIA), a tile with no value is not shown.

## Part III · Where the data comes from

### 12. The sources and their licences

| Source (`SOURCE` of the refresh script) | What it gives | Where | Licence |
|---|---|---|---|
| `nport` · SEC Form N-PORT data sets | holdings with CUSIP, ISIN, LEI, asset and issuer type, value and weight; derivatives with notional, expiry and counterparties; repos; flows; monthly returns per share class | `https://www.sec.gov/data-research/sec-markets-data/form-n-port-data-sets`, quarterly ZIP (about 450 MB; 32 tab-delimited tables documented in the data set's `nport_readme.htm` and the SEC's N-PORT readme PDF) | public domain (U.S. Government work) |
| `ncen` · SEC Form N-CEN data sets | ETF flag and facts (creation unit, exchange), index-fund flag, adviser, sub-advisers, custodian, administrator, transfer agent, auditor, authorised participants, share classes | `https://www.sec.gov/data-research/sec-markets-data/form-n-cen-data-sets`, quarterly ZIP (6 to 16 MB) | public domain |
| `rr` · SEC mutual fund prospectus risk/return summary data sets | net and gross expense ratio, management fee, portfolio turnover, calendar-year returns (`NetExpensesOverAssets`, `ExpensesOverAssets`, `ManagementFeesOverAssets`, `PortfolioTurnoverRate`, `AnnualReturnPercent` / `AnnlRtrPct`) | `https://www.sec.gov/data-research/sec-markets-data/mutual-fund-prospectus-riskreturn-summary-data-sets`, quarterly ZIP (25 to 80 MB) | public domain |
| `tickers` · SEC ticker files | share class tickers with their series and class ids (`company_tickers_mf.json`); a unit investment trust's ticker (`company_tickers_exchange.json`) | `https://www.sec.gov/files/` | public domain |
| `nasdaq` · Nasdaq Trader symbol directory | which tickers are ETFs, and where they list | `https://www.nasdaqtrader.com/dynamic/SymDir/` | public directory files |
| `gleif` · GLEIF golden copy, level 2 | a fund's relationships: managed by, sub-fund of (its umbrella), feeder to | `https://goldencopy.gleif.org` (reused from the gleif pack's cache when present) | CC0 1.0 |

The prospectus risk/return summary data sets were checked: they are published quarterly and carry the expense ratio, turnover and
the bar chart's calendar-year returns as XBRL facts per share class, so the pack uses them (1,032 share classes and series of the 200 funds have a
summary in the four quarters read; a few fund families file the bar chart under a custom tag and show no returns). The pack also reads
the committed us-equities snapshot (companies, their SIC divisions, LEIs and shares outstanding; index members). SEC asks for a
descriptive User-Agent with a contact and at most 10 requests a second: the refresh script uses exactly the us-equities pack's setting
(`DRISHTI_SEC_USER_AGENT`, else the default in `tools/packgen/us_equities/fetch.py`) and one request at a time. The External data page,
[DATA_GUIDE.md](DATA_GUIDE.md#funds), lists every source of every pack.

### 13. How the 200 are chosen

From the newest N-PORT data set: every fund's newest report in the quarter (an amendment wins over the report it amends; a notice of
late filing is skipped), ranked by **net assets** (`FUND_REPORTED_INFO.NET_ASSETS`). A fund needs a ticker: a share class in
`company_tickers_mf.json` (the fund's series id), or, for a unit investment trust without a series id (SPY, DIA), the listed ticker of
its registrant in `company_tickers_exchange.json`. The 200 largest with a ticker are kept: 117 mutual funds, 50 ETFs, 31 mutual funds
with an ETF share class and 2 unit investment trusts, from the Vanguard Total Stock Market Index Fund (2,297.8bn) to the Fidelity
Advisor Growth Opportunities Fund (35.3bn). Money market funds file Form N-MFP, not N-PORT, so none is among them; interval and
private-credit funds without a ticker are skipped. `--scope FUNDS:<n>` takes more or fewer.

### 14. How the documents are built

`tools/packgen/funds/make.py` builds every document offline from the downloads (`fundsread.py` streams the tables, `fundsbuild.py` builds):

1. The N-PORT tables are read once, in order: `SUBMISSION`, `REGISTRANT` and `FUND_REPORTED_INFO` choose the 200; then only their rows
   of `FUND_REPORTED_HOLDING` (411,796 of 5.6 million), `IDENTIFIERS` (ticker, ISIN), the derivative tables and `MONTHLY_TOTAL_RETURN`.
2. N-CEN (the newest four quarters, the newest report of each fund) and the risk/return summaries (the newest four quarters, the
   newest filing of each share class) add the census and the fees; GLEIF's relationship file adds the fund's manager and umbrella.
3. A holding is linked to its us-equities company (by ticker, else CUSIP, LEI or name), to another fund of the pack (by ticker), and
   to its LEI. The holdings are sorted by value; a fund keeps its 600 largest (`--scope HOLDINGS:<n>`), with the count, weight and
   value of the rest.
4. The groupings (asset type, issuer type, country, SIC division), concentration, flows and counterparties are worked out; then the
   reverse look-through (`fund-holder`) from every holding of every fund; then the index weights and each fund's comparison (section
   4); then the funds tracking each index.
5. `tools/testdata/publicsnap.py` writes each kind as gzipped JSON Lines, the fund kind in parts of about 1.2 MB, and lists them in
   `data/testdata/testdata.yaml`; the generator also writes `samples/` (QQQ, AAPL and NDX as reference documents), the README, the
   glossary and the pack guide.

### 15. The refresh script

`tools/publicdata/funds.py` (with `tools/publicdata/funds.sh` and `tools\windows\publicdata-funds.ps1`) takes the same commands and
options as every public-data script: `status`, `refresh --dry-run`, `refresh [SOURCE ...]`, with `--since YEAR`, `--years N`,
`--scope FUNDS:<n>,HOLDINGS:<n>`, `--offline`, `--cache DIR`, `--max-age HOURS` (a week by default), `--pause`, `--no-check`,
`--into-pack`.

On Linux and macOS:

```bash
tools/publicdata/funds.sh status                                # offline: the committed funds, each source's licence, last refresh, cache size
tools/publicdata/funds.sh refresh --dry-run                     # the quarters and files a refresh would fetch; changes nothing
tools/publicdata/funds.sh refresh                               # download (about 700 MB the first time), build, check
tools/publicdata/funds.sh refresh --offline                     # rebuild from the downloads only
tools/publicdata/funds.sh refresh ncen rr --since 2025          # every census and summary quarter since 2025
tools/publicdata/funds.sh refresh --scope FUNDS:300,HOLDINGS:1000   # a wider build in <data>/publicdata/funds
python3 tools/drishti.py lake demo                              # then rebuild the lake
```

```text
$ tools/publicdata/funds.sh status
committed pack: 200 funds in 5 parts, data retrieved 2026-10-26
SOURCE   LICENCE                                           USE          LAST REFRESHED       CACHED
tickers  Public domain (U.S. Government work); SEC fair a  committable  2026-10-26T19:37:15  1.7 MB
nasdaq   Public symbol directory files (Nasdaq Trader)     committable  2026-10-26T19:37:15  871.4 KB
nport    Public domain (U.S. Government work); SEC fair a  committable  2026-10-26T19:12:40  441.0 MB
ncen     Public domain (U.S. Government work); SEC fair a  committable  2026-10-26T19:37:15  36.4 MB
rr       Public domain (U.S. Government work); SEC fair a  committable  2026-10-26T19:37:15  187.4 MB
gleif    CC0 1.0                                           committable  2026-10-26T19:37:15  23.4 MB
```

On Windows (PowerShell, from the repository folder; Python only, nothing to install):

```powershell
.\tools\windows\publicdata-funds.ps1 status
.\tools\windows\publicdata-funds.ps1 refresh --dry-run
.\tools\windows\publicdata-funds.ps1 refresh
python tools\build_demo_lakes.py --only funds
```

The dry run names every request and writes nothing:

```text
$ tools/publicdata/funds.sh refresh --dry-run
dry run: funds, sources tickers, nasdaq, nport, ncen, rr, gleif
  tickers (Public domain (U.S. Government work); SEC fair access policy; committable): 2 request(s), cache <data>/publicdata/cache/funds
    https://www.sec.gov/files/company_tickers_mf.json
    https://www.sec.gov/files/company_tickers_exchange.json
  nport (Public domain (U.S. Government work); SEC fair access policy; committable): 3 request(s), cache <data>/publicdata/cache/funds
    https://www.sec.gov/files/dera/data/form-n-port-data-sets/2026q3_nport.zip
    https://www.sec.gov/files/dera/data/form-n-port-data-sets/2026q2_nport.zip
    https://www.sec.gov/files/dera/data/form-n-port-data-sets/2026q1_nport.zip
  ...
  then: tools/packgen/funds/make.py -> config/packs/funds and data/testdata; then make.py --check
```

A quarterly source tries the newest quarters first and takes the published ones (N-PORT one, N-CEN and the summaries four); a quarter
not yet published answers 404 and is passed over. The downloads go to `<data>/publicdata/cache/funds` (`DRISHTI_DATA_DIR`, else the
repository's git-ignored `data/`), each recorded in `cache-index.json` with its URL, time, size and checksum; the run is recorded in
`<data>/publicdata/funds/manifest.json`. A widened build goes to `<data>/publicdata/funds` (`packs/funds`, `testdata/funds`); only
`--into-pack` writes it over the committed pack.

### 16. The gzipped snapshot and offline use

The committed data is small and gzipped, so a clone (and a Windows checkout) stays light: `data/testdata/funds/fund/` holds five parts
of about 1 MB (`snapshot-2026-10-26.part-01.jsonl.gz` to `part-05`), `fund-holder/` and `index-funds/` one file each, **5.6 MB in
all**. `tools/testdata/test_testdata.py` fails if any data file under `data/testdata` is not gzipped or is over 5 MB. The downloads
(about 700 MB) never enter git.

Offline, nothing is fetched: `python3 tools/drishti.py lake demo` builds the three tables from the committed snapshot (85 tables in the whole demo lake), and the server reads them with offline mode on. `refresh --offline` rebuilds the pack from the downloads already in
the cache; without them, `python3 tools/packgen/funds/make.py --check` checks the committed snapshot against what it derives (the
samples, README, glossary and guide).

### 17. What is not included

- **Prices and NAV history.** There is no free official source; the pack needs none (the index weights use the prices the filings
  imply). Yahoo Finance prices exist only through the us-equities pack's internal-only, off-by-default connector.
- **Index weights** (proprietary): computed from SEC data, as section 4 says.
- **Non-US funds, money market funds** (Form N-MFP), and funds without a ticker.
- **More than 600 holdings a fund** in the fund documents (the reverse look-through counts them all); `--scope HOLDINGS:<n>` keeps
  more in a widened build.

## Part VII. Groupings and analytics, computed

*Revision 1.0 · 2026-10-26 · release 2.9.2 · the one reference for the groupings and analytics computed instead of stored. The
hands-on companion is [tutorial 27](../tutorials/27-groupings-and-analytics.md).*

Seven pages that used to be **stored copies** of figures are **computed from the fundamental data when they are opened** since 2.9.2:

| Command | What you open | Computed from | By |
|---|---|---|---|
| `LEIG` (`lei-group`) | an ownership group: its tree, members, counts | every GLEIF legal entity in the lake | the derived connector `gleif-groupings` |
| `LEIC` (`lei-country`) | a country: its entities, groups, lapsed and late renewals | every GLEIF legal entity | `gleif-groupings` |
| `SIC` (`sic-sector`) | a SIC sector: its companies, revenue, net income | every us-equities company | `us-equities-groupings` |
| `ECG` (`country-group`) | a World Bank region, income group or the world | every economy | `macro-groupings` |
| `CORR` (`correlation-matrix`, `correlation-result`) | a factor set's correlations | the factors' daily closes | the reactive `correlation-engine` (Java) |
| `USEQ` ratios (`us-company`), `FACT` changes, `IDX` totals | net margin, revenue growth, a fact's change on the year, an index's members' revenue and divisions | the company's financial facts, the index's members | the page itself |
| `PXH` analytics (`price-history`) | returns, volatility, drawdown, beta | the price history just read | the price connector (`yfinance`) |
| `EQB` (`equity-beta`; the `EQ` page's beta) | an equity's beta against its index | the equity's and the index's daily closes | the reactive `equity-analytics` (Java) |

The rule is the one the [implementation plan](../architecture/IMPLEMENTATION_PLAN.md#groupings-and-analytics-track-b-shipped-in-290-or-2100-as-each-is-ready)
sets for every computed measure: **fundamental data is kept as it is; whatever can be derived from it is computed when asked and never
stored**, and the stored version is retired in the same release. A stored copy of a computed figure drifts: the stored ownership groups
were capped at 50 members, the stored countries listed 400 entities, the stored correlations were synthetic and did not match the prices in
the same lake.

**Part I** shows each command on the committed data, **Part II** is the data contract of every computed kind (every column, its formula,
its inputs and its errors) with the reconciliation against the retired stored pages, and **Part III** says how each is computed and where
its inputs come from.

### Contents

- **Part I · Using them**
  - [1. What changed for you](#1-what-changed-for-you)
  - [2. LEIG: an ownership group](#2-leig-an-ownership-group)
  - [3. LEIC: a country](#3-leic-a-country)
  - [4. SIC: a sector](#4-sic-a-sector)
  - [5. ECG: a country group](#5-ecg-a-country-group)
  - [6. CORR: correlations from price history](#6-corr-correlations-from-price-history)
  - [7. USEQ, FACT and IDX: margins, changes and totals](#7-useq-fact-and-idx-margins-changes-and-totals)
  - [8. PXH: returns, volatility and beta](#8-pxh-returns-volatility-and-beta)
  - [9. Old ids, links and commands](#9-old-ids-links-and-commands)
- **Part II · The data contract**
  - [10. The contract at a glance](#10-the-contract-at-a-glance)
  - [11. lei-group](#11-lei-group)
  - [12. lei-country](#12-lei-country)
  - [13. sic-sector](#13-sic-sector)
  - [14. country-group](#14-country-group)
  - [15. correlation-result](#15-correlation-result)
  - [16. The USEQ ratios](#16-the-useq-ratios)
  - [17. The PXH analytics](#17-the-pxh-analytics)
  - [18. Reconciliation: stored against computed](#18-reconciliation-stored-against-computed)
- **Part III · How they are computed**
  - [19. The derived connector](#19-the-derived-connector)
  - [20. The correlation engine](#20-the-correlation-engine)
  - [21. The price analytics](#21-the-price-analytics)
  - [22. Where the inputs come from](#22-where-the-inputs-come-from)
  - [23. The retirement guard](#23-the-retirement-guard)
  - [24. Settings and limits](#24-settings-and-limits)

---

## Part I · Using them

### 1. What changed for you

Very little on the screen, a lot underneath:

- **The commands, ids and links are the same.** `LEIG 8I5DZWZKVSZI1NUHU748`, `LEIC GB`, `SIC 3674`, `ECG ECG-SAS` and `CORR CORR-EU-EQ` open as
  before; a bookmark, a link from another page, an export or a BI dataset that names one of them reads the computed page. The `ALL`
  directories (`LEIG ALL`, `LEIC ALL`, `SIC ALL`) are computed too.
- **No cap.** A group lists every member the lake holds, a country every entity (the stored country page listed the first 400).
- **The pill says computed.** Each page's title pill ends in *(computed)*, and an explainer on the right says how.
- **New figures** that the stored pages could not have: LEIG's lapsed registrations and each member's level, SIC's net margin and the
  number of filers not reporting in USD, ECG's median inflation and every member economy, CORR's window and estimator, PXH's returns,
  volatility and beta.
- **Gone:** the stored figures themselves (the `lei-group`, `lei-country` and `sic-sector` snapshots, the stored country groups, the
  stored correlation pairs and the stored ratios). [Section 18](#18-reconciliation-stored-against-computed) explains every difference.

The pages are computed **once per business date** and shared by every reader (a refresh of 60 seconds, rebuilt when the lake loads), so
opening one is as fast as before. A date picked in the top bar computes that date's pages from that date's entities.

### 2. LEIG: an ownership group

```text
LEIG 8I5DZWZKVSZI1NUHU748 <GO>
```

JPMorgan Chase & Co.'s group, computed from the 50 legal entities of the committed data whose ownership group (`LEI_GROUP`) it is:

| Strip | Value | How |
|---|---|---|
| Members | 50 | the entities of the group |
| Members (GLEIF) | 173 | the ultimate parent's `GLEIF_GROUP_SIZE` (GLEIF's golden copy) |
| Levels | 2 | the deepest level of the tree: 1 ultimate parent, 20 entities at level 1, 29 at level 2 |
| Countries | 13 | US 27, GB 8, BR 3, IE 2, AU 2, ... |
| Late renewals | 5 | members whose LEI renewal is late |
| Home | United States of America (the) | the ultimate parent's country |

The **tree** opens two levels: the ultimate parent, its direct children, theirs. It is built from each member's `DIRECT_PARENT`; a member
whose direct parent is not in the group hangs under the ultimate parent. The **members** table lists all 50 with their country and level;
a click opens the member's key facts beside it. The **Cap** line says *GLEIF has 173 entities in the group; the committed data keeps 50*:
the committed snapshot keeps at most 50 entities a group to stay small, the computation itself has no cap (a wider refresh, [section 22](#22-where-the-inputs-come-from),
brings more and the page counts them all).

Other groups: `LEIG MLU0ZO3ML4LN2LL2TL39` (HSBC Holdings: 50 kept of 461 in GLEIF, one level, 18 countries, 9 late),
`LEIG HWUPKR0MPOU8FGXBT394` (Apple: the whole group, 21 of 21, three levels, 15 countries, none late). `LEIG ALL` is the pack's front page:
9,937 entities in 590 groups across 133 countries, 2,256 lapsed and 2,288 late renewals, every group's ultimate parent largest in GLEIF
first, and the search over every entity.

The type-ahead finds a group by its ultimate parent's name: `LEIG Ford Motor` suggests Ford's group.

### 3. LEIC: a country

```text
LEIC GB <GO>
```

| Strip | Value |
|---|---|
| Entities | 1,274 |
| Groups present | 263 |
| Groups headquartered | 27 |
| Lapsed | 225 |
| Late renewals | 227 late: renewal failed |

Every one of the 1,274 entities is listed by name (the stored page stopped at 400), with its city and the late flag; a click opens the
entity. The groups table counts each ownership group's entities here, largest first (the group key, a link to `LEIG`). On the right:
entities by legal form and by city (`LONDON` 494, `London` 245, `EDINBURGH` 36: GLEIF keeps the city as each entity wrote it).
`LEIC US`: 2,704 entities, 521 groups present, 451 headquartered, 713 lapsed, 724 late. `LEIC ALL`: 133 countries, 9,937 entities,
each country with its entities, largest first.

### 4. SIC: a sector

```text
SIC 3674 <GO>
```

Semiconductors & Related Devices: 20 companies; revenue 613.8bn and net income 256.2bn USD over their latest fiscal years; **net margin
41.7%**; none reporting in another currency. The companies are ranked by revenue (NVDA 215.9bn at a 55.6% margin, MU 133.2bn, AVGO 63.9bn,
INTC 52.9bn at −0.5%, AMD 34.6bn); a click opens a company's key facts and ten years of financials beside the table.

`SIC 6022` (State Commercial Banks) shows the currency rule: 15 companies, revenue 70.0bn and net income 25.5bn USD over **14**: the
fifteenth, BNS, reports in CAD, so it is counted and listed but **not summed** (the strip says *1 not summed*). `SIC ALL` lists the 194
codes and totals revenue by SIC division: 586 companies, 18.43 trillion USD of revenue (the 573 USD reporters), 2.34 trillion of net income.

### 5. ECG: a country group

```text
ECG ECG-SAS <GO>
```

South Asia, a World Bank region, computed from its six member economies:

| Strip | Value | How |
|---|---|---|
| Economies | 6 | every economy whose region or income group is ECG-SAS |
| GDP (USD bn) | 4,578 | the members' GDP summed |
| GDP per capita (USD) | 2,705 | that GDP over the members' population (1,692.26m) |
| Real growth | 7.07% | weighted by GDP: India 3,956bn at 7.57%, Bangladesh 456bn at 3.49%, ... |
| Inflation (median) | 3.10% | the median of the members, as the World Bank aggregates prices |
| Latest year (members table) | 2025 | the latest year among the members' figures (each member's own in its row) |

Below: the largest economies, every member economy (a click opens it), and the GDP path, the members' GDP summed by year. `ECG ECG-WLD`
(the world): 213 economies, 117,327bn USD, 8,161.8m people, 14,375 USD a head, growth 2.80%, median inflation 3.11%. `ECG ECG-HIC` (high
income): 84 economies, 75,910bn, 54,209 a head, growth 1.88%: the stored page had **no figures** for the income groups, the computed one
has them all.

### 6. CORR: correlations from price history

```text
CORR CORR-EU-EQ <GO>
```

The correlation set keeps only its definition: *European equities*, four factors (EQ-ORBT, EQ-RHNB, EQ-GRNT, EQ-LUMN). The panels ask the
computed kind `correlation-result` for the window and estimator picked at the top of the page:

| Window | Estimator | Returns used | Average ρ | Lowest | Highest |
|---|---|---|---|---|---|
| All (0, the default) | Pearson (the default) | 29 (the test data's 2026-08-19 to 2026-09-30) | 0.096 | −0.156 (RHNB, GRNT) | 0.382 (RHNB, LUMN) |
| 20 | Pearson | 20 (from 2026-09-01) | 0.142 | −0.165 | 0.407 |
| All | Ledoit-Wolf | 29 | 0.000 | 0.000 | 0.000 (shrinkage 1.00) |

Ledoit-Wolf shrinks the sample correlations toward none by the intensity that minimises the expected error; on 29 returns of four
factors whose sample correlations are within noise, the optimal intensity is 1 and every pair is shrunk to zero. That is the estimator
saying *these 29 days do not show a correlation you can trust*; a longer history moves it below 1. The factors table gives each factor's
last close, its return over the window and its annualised volatility (EQ-ORBT 22.7%).

The computed kind opens on its own too: `CORRC CORR-G10FX` (G10 FX, Pearson over 29 returns: average −0.012, EURUSD and GBPUSD 0.222),
with parameters riding on the id: `CORRC CORR-G10FX~estimator=ledoit-wolf~window=20`.

### 7. USEQ, FACT and IDX: margins, changes and totals

```text
USEQ AAPL <GO>
```

The strip's **Net margin** (26.9%) and **Revenue growth** (+6.4%) are worked out as the page opens from the company's own facts: fiscal 2025
revenue 416.16bn and net income 112.01bn; growth against fiscal 2024's revenue in the financials table. The financials table's net
margin column is computed per year the same way. `USEQ JPM`: 31.3% and +2.8%; `USEQ F`: −4.4% and +1.2%. The index pages (`IDX SP500`)
and the sector pages compute a member's margin the same way.

`FACT AAPL-REVENUE` works out each year's change against the year above it in the table, and the strip's *Change on the year* from the
last two values (+6.4%). `IDX SP500` adds up its 495 members' rows as it opens: revenue 17.85 trillion and net income 2.28 trillion USD
(the USD reporters), 9 SIC divisions, members by division on the right.

### 8. PXH: returns, volatility and beta

```text
PXH PX-AAPL <GO>
```

**Internal use only, and off as shipped**, like the prices themselves (`DRISHTI_US_EQUITIES_PRICES_ENABLED=false`). With the connector on
(a site's decision: free price feeds forbid redistribution), the page's **Analytics** panel shows, computed from the year of daily closes
the connector has just read: the 1, 3 and 6 month returns and the return over the whole history, the annualised volatility of the daily
log returns, the largest drawdown, and beta and correlation against the benchmark (`SPY` unless the site sets `DRISHTI_US_EQUITIES_BENCHMARK`).
Nothing is stored: the analytics are as fresh, and as internal, as the prices. With the connector off the page has no data.

#### 8.1 EQB: an equity's beta from price history

```text
EQ EQ-NVTK <GO>          the equity page: its Beta panel
EQB EQ-NVTK <GO>         the computed kind itself
```

The security master's stored `BETA` (1.28 for Novatek) is gone. The equity page's **Beta** panel asks the computed kind `equity-beta`
for the equity's beta against its index (`EQUITY_INDEX`: EQX-NDX), the regression of the two daily log return series on the dates both
have: over the 29 returns to 2026-09-30, **−0.07** with a correlation of −0.03. The demo's prices are independent synthetic walks, so
their measured betas are near zero; the stored ones were set by hand. An equity whose record names no index (EQ-CSCA, EQ-LUMN) gets a
refusal in words instead of a number. `EQB EQ-NVTK~window=20` takes the last 20 returns. `EQB` has its own page since 2.9.2
(`config/packs/market-data/sutras/market-data/equity-beta.v1.sutra.yaml`): the beta, correlation and R² in the strip to 2 decimals, and
the regression to 4 (beta −0.0725, alpha −0.0881% a day, correlation −0.0333, R² 0.0011, volatility 26.72% against the index's 12.27%),
where the inferred layout had rounded them to whole numbers ("0").

### 9. Old ids, links and commands

| Old | Now |
|---|---|
| `LEIG <LEI>`, `/v/lei-group/<LEI>`, `LEIG ALL` | the same ids, computed from the entities (no member cap); `MEMBERS_IN_GLEIF` from the ultimate parent's `GLEIF_GROUP_SIZE` |
| `LEIC <cc>`, `/v/lei-country/<cc>`, `LEIC ALL` | the same ids, computed (no 400-row cap) |
| `SIC <code>`, `/v/sic-sector/<code>`, `SIC ALL` | the same ids, computed from the companies |
| `ECG ECG-<code>`, `/v/country-group/ECG-<code>` | the same ids, computed from the economies |
| `CORR CORR-<set>`, `/v/correlation-matrix/CORR-<set>` | the same ids: the set's definition, its correlations computed (`CORRC CORR-<set>`) |
| `us-company` fields `NET_MARGIN`, `REVENUE_GROWTH`, `FINANCIALS[].NET_MARGIN`; `index-membership` `ROWS[].NET_MARGIN` | removed from the records; the pages compute them (a saved search on `NET_MARGIN` no longer finds anything) |
| `correlation-matrix` fields `AVERAGE`, `PAIRS`, `WINDOW`, `ESTIMATOR` | removed; `correlation-result` answers them |
| `financial-fact` `CHANGE_PCT`, `HISTORY[].CHANGE_PCT` | removed; the page computes the change |
| `index-membership` `SECTORS`, `REVENUE`, `NET_INCOME`, `BY_SECTOR` | removed; the page sums the members' rows (the membership and `MEMBERS` stay) |
| `equity` `BETA` | removed; `EQB <ticker>` and the equity page's Beta panel compute it |
| the search form of `lei-group` (`FORM gleif`) | gone: the type-ahead finds a group by its name, and the `LEIG ALL` search finds entities |

---

## Part II · The data contract

### 10. The contract at a glance

Every computed kind is an ordinary kind: it opens by command, link, API, CLI and AI, with the reader's entitlements and masks applied to
its inputs. The machine-readable contracts are `config/packs/<pack>/contracts/<kind>.contract.yaml` (class `computed`; the pages
[docs/contracts/packs/](../contracts/packs/README.md)); this part says what each field means.

| Kind | Command | Connector (plugin) | Key | Inputs | Dated by |
|---|---|---|---|---|---|
| `lei-group` | LEIG | `gleif-groupings` (derived) | `GROUP_ID`: an ultimate parent's LEI, or `ALL` | `lei-entity` | the business date asked (the entities' date) |
| `lei-country` | LEIC | `gleif-groupings` (derived) | `COUNTRY`: an ISO 3166 code, or `ALL` | `lei-entity` | as above |
| `sic-sector` | SIC | `us-equities-groupings` (derived) | `SIC`: a four-digit code, or `ALL` | `us-company` | as above |
| `country-group` | ECG | `macro-groupings` (derived) | `GROUP_ID`: `ECG-<World Bank code>` | `economy` | as above |
| `correlation-result` | CORRC | `correlation-engine` (reactive, Java) | the set's id, `~window=`, `~estimator=` | `correlation-matrix`, `equity`, `fx-spot` | the request |
| USEQ ratios | USEQ | none: the `us-company` page | `TICKER` | `us-company` | the company record's |
| PXH analytics | PXH | `us-equities-prices` (yfinance) | `PX-<ticker>` | the price history read live | the request (live only) |
| `equity-beta` | EQB | `equity-analytics` (reactive, Java) | the equity's id, `~window=` | `equity`, `equity-index` | the request |
| FACT changes, IDX totals | FACT, IDX | none: the pages | `FACT_ID`, `INDEX` | `financial-fact`, `index-membership` | the record's |

Every derived page also carries `id` (the key), `derivedFrom` (the kind it groups), `memberCount` and the member ids (`MEMBER_LEIS`,
`TICKERS`, `MEMBER_ECONOMIES`). A pane `ALL` keeps its aggregates and the lists its `then` fields make, but **not** its member ids, rows or
tree (`unlisted`, [section 19](#19-the-derived-connector)).

**Number rules:** counts are whole numbers; `sum` skips a member without the figure (a missing number is never a zero); a ratio whose
denominator is zero or missing is empty; percentages are as published (12.3 means 12.3%) except where a field says *a fraction*.

### 11. lei-group

One page per ultimate parent: the members are every `lei-entity` whose `LEI_GROUP` is the key. Every entity is also in `ALL`.

| Field | Type | Formula | Notes |
|---|---|---|---|
| `GROUP_ID` | id | the key | the ultimate parent's LEI |
| `MEMBERS` | integer | count of members | no cap |
| `LATE_RENEWALS` | integer | count where `RENEWAL_LATE` | |
| `LAPSED` | integer | count where `REGISTRATION_STATUS == 'LAPSED'` | new in 2.9.2 |
| `COUNTRY_LIST`, `GROUP_LIST` | text list | the distinct `LEI_COUNTRY`, `LEI_GROUP` | helpers |
| `ROOT_NAME`, `ROOT_COUNTRY`, `ROOT_COUNTRY_NAME`, `ROOT_LEGAL_FORM`, `ROOT_SEGMENT`, `ROOT_US_COMPANY` | text list | the field of the member whose `LEI == LEI_GROUP` | helpers |
| `GLEIF_GROUP_SIZE` | integer | the ultimate parent's `GLEIF_GROUP_SIZE` | an input from GLEIF |
| `NAME`, `LEI_COUNTRY`, `COUNTRY_NAME`, `LEGAL_FORM`, `SEGMENT`, `US_COMPANY` | text | the first of the matching `ROOT_*` | `NAME` is *Every ownership group of the pack* on `ALL` |
| `ENTITY_LEI` | id | the key (empty on `ALL`) | a link to the entity |
| `COUNTRIES`, `GROUPS` | integer | `size(COUNTRY_LIST)`, `size(GROUP_LIST)` | |
| `MEMBERS_IN_GLEIF` | integer | `max(GLEIF_GROUP_SIZE, MEMBERS)`; `MEMBERS` when GLEIF says nothing | |
| `CAPPED` | boolean | `GLEIF_GROUP_SIZE > MEMBERS` | the committed snapshot keeps at most 50 a group |
| `ROWS[]` | record list | one row a member: `LEI`, `NAME`, `COUNTRY`, `CITY`, `LEGAL_FORM` (*Not given* when blank), `PARENT` (`DIRECT_PARENT`), `STATUS`, `LATE`, `SEGMENT`, `GROUP`, `GLEIF_GROUP_SIZE`, `US_COMPANY`, `LEVEL` | in member id order |
| `TREE` | record list | the rows nested under the key by `PARENT`; each node a row with `CHILDREN` | a parent outside the group, a missing one or a cycle hangs the row under the key |
| `LEVELS` | integer | the deepest `LEVEL` (the key is 0) | |
| `BY_COUNTRY`, `BY_LEGAL_FORM`, `BY_SEGMENT` | record list | `group(ROWS, field)`: rows of `label`, `value` (members), `count`; largest first, the top 20 and 15 | |
| `GROUP_ROWS` | record list | on `ALL`: the rows of every ultimate parent, largest `GLEIF_GROUP_SIZE` first | empty on a group |
| `PROVENANCE` | record | the first member's (GLEIF, CC0, the retrieval date) | |

**Errors:** an id that is no entity's group answers *entity not found* (`DRS-1001`, as any missing entity). A member without `LEI_GROUP` is in no
group (none in the committed data). A field a member lacks is skipped; an expression that fails on one member leaves that member's value
empty and never fails the page.

### 12. lei-country

One page per legal-address country (`LEI_COUNTRY`), and `ALL`.

| Field | Type | Formula |
|---|---|---|
| `COUNTRY` | id | the key |
| `ENTITIES` | integer | count of members |
| `GROUPS_HEADQUARTERED` | integer | count where `LEI == LEI_GROUP` (an ultimate parent with its legal address here) |
| `LAPSED`, `LATE_RENEWALS` | integer | as in `lei-group` |
| `NAME` | text | the members' `COUNTRY_NAME` (*Every country of the entities in the pack* on `ALL`) |
| `COUNTRIES`, `GROUPS_PRESENT` | integer | the distinct countries and groups among the members |
| `ROWS[]` | record list | one row an entity: `LEI`, `NAME`, `COUNTRY`, `COUNTRY_NAME`, `CITY`, `LEGAL_FORM`, `GROUP`, `STATUS`, `LATE`; by name; **every** entity |
| `BY_LEGAL_FORM`, `BY_CITY` | record list | `group(ROWS, ...)`, the top 15 |
| `GROUP_ROWS` | record list | `group(ROWS, 'GROUP')`: each group key with its entities here, largest first |
| `BY_COUNTRY` | record list | by country name, the top 20 (useful on `ALL`) |
| `COUNTRY_ROWS` | record list | on `ALL`: every country code with its entities, largest first |
| `PROVENANCE` | record | the first member's |

### 13. sic-sector

One page per SIC code reported by at least one us-equities company, and `ALL`.

| Field | Type | Formula |
|---|---|---|
| `SIC` | id | the key |
| `COMPANIES` | integer | count of companies (every currency) |
| `REVENUE` | amount | sum of `REVENUE` over the companies with `CURRENCY == 'USD'` |
| `NET_INCOME` | amount | the same for `NET_INCOME` |
| `NET_MARGIN` | rate | `NET_INCOME / REVENUE × 100` (empty when `REVENUE` is 0) |
| `NON_USD` | integer | count with `CURRENCY != 'USD'`: listed, not summed |
| `DESCRIPTION`, `DIVISION`, `SECTOR` | text | the companies' `SIC_DESCRIPTION`, `DIVISION`, `SECTOR` |
| `CODES` | integer | the distinct SIC codes (181 on `ALL`) |
| `ROWS[]` | record list | one row a company: `TICKER`, `NAME`, `EXCHANGE`, `INDEX_TEXT`, `SECTOR`, `REVENUE`, `NET_INCOME`, `NET_MARGIN` (the company's, computed), `CURRENCY`, `FISCAL_YEAR`; largest revenue first |
| `BY_REVENUE` | record list | the USD reporters with revenue, the top 15 |
| `BY_DIVISION` | record list | `group(ROWS[USD], 'SECTOR', 'REVENUE')`, largest first |
| `PROVENANCE` | record | the first company's (SEC EDGAR, public domain) |

**Why USD only:** a sum of USD, CAD, GBP and EUR figures means nothing; the stored pages did the same, so the totals reconcile exactly.

### 14. country-group

One page per World Bank region and income group, and the world. An economy is in its `REGION_GROUP`, its `INCOME_GROUP` and `ECG-WLD`;
each counts at its latest year (`YEAR`).

| Field | Type | Formula | Weights |
|---|---|---|---|
| `GROUP_ID` | id | the key | |
| `ECONOMIES` | integer | count of member economies | |
| `GDP_USD_BN` | amount | sum of `GDP_USD_BN` | |
| `POPULATION_M` | amount | sum of `POPULATION_M` | |
| `GDP_PER_CAPITA` | amount | `GDP_PC_GDP × 1000 / GDP_PC_POP`: GDP over population, both summed over the members that have both | |
| `GROWTH` | rate | `GROWTH_WEIGHTED / GROWTH_WEIGHT` = Σ GDP·growth / Σ GDP over the members with both | GDP in USD |
| `INFLATION` | rate | the median of the members' `INFLATION` (an even count: the mean of the middle two) | none: the median, as the World Bank aggregates consumer prices |
| `WITH_GROWTH`, `WITH_INFLATION` | integer | the members each figure is taken over | |
| `NAME`, `GROUP_TYPE` | text | the region's or income group's name (from the members' `REGION_NAME`, `INCOME_NAME`), or *World* | |
| `YEAR` | text | the latest of the members' years | |
| `ROWS[]` | record list | one row a member: `ECONOMY_ID`, `ECONOMY`, `ISO3`, `GDP_USD_BN`, `POPULATION_M`, `GDP_PER_CAPITA`, `GROWTH`, `INFLATION`, `YEAR`, `GDP_PATH`; largest GDP first | |
| `TOP_ECONOMIES` | record list | the ten largest rows | |
| `GDP_PATH` | record list | `group(flatten(ROWS[*].GDP_PATH), 'YEAR', 'GDP')`: each year's GDP summed over the members with a figure that year (`label` year, `value` USD bn, `count` members) | |
| `PROVENANCE` | record | the first member's (World Bank WDI CC BY 4.0, IMF, CIA Factbook) | |

The stored page's `GROWTH_PATH` (the World Bank's aggregate growth by year) is not computed: a GDP-weighted growth per year needs each
member's GDP and growth of the same year joined, which the page does not do; each member economy's page has its own growth path.

### 15. correlation-result

The answer for one correlation set (`correlation-matrix`, which keeps `MATRIX_ID`, `NAME`, `FACTORS`, `EQUITY_IDS`, `FX_IDS`), one window
and one estimator.

**Parameters** (on a panel's `source: params`, on the id as `CORR-EU-EQ~window=20~estimator=ledoit-wolf`, or `--param` on the CLI):

| Parameter | Type | Default | Allowed |
|---|---|---|---|
| `window` | int | `0`: every return the history holds | 0 to 5,000 daily returns |
| `estimator` | enum | `pearson` | `pearson`, `ledoit-wolf` |

**The computation:**
1. Read the set, then each factor's document: an equity's `HISTORY[]` of `DATE`, `CLOSE`; an FX pair's of `DATE`, `MID` (as of the business
   date asked; the committed data holds the last 30 closes on each date).
2. Keep the dates **every** factor has; take the last `window + 1` closes (all when `window` is 0 or longer than the history).
3. Daily log returns `r_t = ln(P_t / P_{t-1})`; each factor's mean `μ` and standard deviation `σ` (divided by `n`).
4. **Pearson:** `ρ_ij = (1/n) Σ_t z_it z_jt` with `z = (r − μ)/σ`.
5. **Ledoit-Wolf** (2004, toward the scaled identity): `S` the matrix above, `m = tr(S)/p`, `d² = ‖S − mI‖²/p`,
   `b̄² = (1/n²) Σ_t ‖z_t z_tᵀ − S‖²/p`, `b² = min(b̄², d²)`, intensity `δ = b²/d²`; `Σ* = (1−δ)S + δmI`, read back as correlations.

| Field | Type | Meaning |
|---|---|---|
| `MATRIX_ID`, `NAME` | id, text | the set |
| `FACTORS` | id list | the factors, equities then FX |
| `ESTIMATOR`, `WINDOW`, `AVAILABLE` | text, integer, integer | the estimator, the returns used, the returns the common dates allow |
| `FROM`, `TO` | date | the window's first and last close |
| `AVERAGE`, `MIN_RHO`, `MAX_RHO` | rate | the mean, lowest and highest pairwise ρ (four decimals) |
| `SHRINKAGE` | rate | Ledoit-Wolf only: `δ` |
| `PAIRS[]` | record list | `A`, `B`, `RHO` for every pair, in factor order |
| `FACTOR_STATS[]` | record list | `FACTOR`, `LAST` (the window's last close), `RETURN` (simple, a fraction), `VOLATILITY` (`σ√252`, a fraction) |
| `STAMP` | record | how the answer was computed (pin, inputs, engine, where it ran), as every reactive answer |

**Errors** (the answer's state is *failed* and its message says which, as `drishti.py compute run` prints it; a parameter outside its
options is *refused* before any work: `estimator kendall is not allowed for correlation-result; allowed: pearson, ledoit-wolf`; a set
that does not exist opened as a page is `DRS-1001`): no such set; fewer than two factors; a factor without price history;
fewer than three common dates; a price of zero or less; a factor that did not move over the window (its correlation is undefined); an
estimator other than the two.

### 16. The USEQ ratios

Computed by the `us-company` page (and by the index, sector and company-card panels) from the record's facts as it is drawn:

| Ratio | Formula | Empty when |
|---|---|---|
| Net margin (strip, card, rows) | `NET_INCOME / REVENUE × 100` | `REVENUE` is 0 or missing |
| Revenue growth (strip) | `(REVENUE − R₋₁) / abs(R₋₁) × 100`, `R₋₁` the `FINANCIALS[]` revenue of `FISCAL_YEAR − 1` | that year is missing or its revenue is 0 |
| Net margin per year (financials table) | `NET_INCOME / REVENUE × 100` of the row | as above |

`REVENUE`, `NET_INCOME`, `FISCAL_YEAR` and `FINANCIALS[]` are the facts as filed with the SEC (XBRL company facts, `financial-fact`); the
ratios are not on the record any more. The same way:

| Page | Figure | Formula |
|---|---|---|
| `FACT` (financial-fact) | change on the year (strip) | `(LATEST − V₋₁) / abs(V₋₁) × 100`, `V₋₁` the `HISTORY[]` value before the last; empty with one value or a zero |
| `FACT` | change (each row) | the row's value against the row above it (the previous reported year) |
| `IDX` (index-membership) | members' revenue, net income | `sum(ROWS[CURRENCY = USD], REVENUE)`, the same for `NET_INCOME` |
| `IDX` | SIC divisions; members by division | `size(group(ROWS, 'SECTOR'))`; `group(ROWS, 'SECTOR')` |

### 17. The PXH analytics

Computed by the price connector (the `yfinance` plugin, `PriceAnalytics`) from the daily closes it has just read, in the field `ANALYTICS`:

| Field | Formula |
|---|---|
| `OBSERVATIONS` | the closes (a year as shipped: `range: 1y`, `interval: 1d`) |
| `RETURN_1M`, `RETURN_3M`, `RETURN_6M` | `P_last / P_{last−k} − 1` for k = 21, 63, 126 trading days; empty when the history is shorter |
| `RETURN_PERIOD` | `P_last / P_first − 1` |
| `VOLATILITY` | the sample standard deviation of the daily log returns × √252 |
| `MAX_DRAWDOWN` | the most negative `P_t / max(P_≤t) − 1` |
| `BENCHMARK` | the connector's `benchmark` setting (`SPY` as shipped) |
| `BETA` | `cov(r, r_b) / var(r_b)` of the daily log returns on the dates both have; empty with fewer than 21 common closes |
| `CORRELATION` | `cov(r, r_b) / (σ σ_b)` on the same dates |

All are fractions (0.123 is 12.3%). The benchmark's history is read through the same connector and cache; when it cannot be read, beta
and correlation are empty and the rest is shown.

#### 17.1 equity-beta

`EQB` (and the equity page's Beta panel): the regression of the equity's daily log returns on its index's (`EQUITY_INDEX`), on the
dates both histories have, over the last `window` returns (parameter `window`, default 0: every return). Engine
`com.ash.drishti.compute.reactive.analytics.BetaEngine`, connector `equity-analytics` (reads `equity` `{id}` and `equity-index`
`{inputs.equity.EQUITY_INDEX}`).

| Field | Type | Formula |
|---|---|---|
| `EQUITY`, `INDEX` | id | the equity asked for, its index |
| `WINDOW`, `AVAILABLE`, `FROM`, `TO` | integer, date | as in `correlation-result` |
| `BETA` | rate | `cov(r, r_i) / var(r_i)` |
| `ALPHA` | rate | `mean(r) − BETA × mean(r_i)`, a daily log return |
| `CORRELATION`, `R_SQUARED` | rate | `ρ(r, r_i)` and its square |
| `VOLATILITY`, `INDEX_VOLATILITY` | rate | the sample standard deviations × √252 (fractions) |

**Errors** (the answer's state *failed*, its message in words: `EQ-CSCA names no index (EQUITY_INDEX): its beta has nothing to be measured
against`): no such equity; an equity that names no index; an index without price history; fewer than three
common dates; an index that did not move over the window.

### 18. Reconciliation: stored against computed

On the committed data (gleif 2026-10-25, us-equities 2026-10-24, world-economy's World Bank and IMF data of 2026-10-10, market data on
2026-09-30), the retired stored pages against the computed ones:

| Kind | Pages | Same | Different, and why |
|---|---|---|---|
| `lei-group` | 590 groups + ALL | members 589, countries 590, late renewals 589, levels 571; ALL: groups 590, entities 9,937, countries 133, late 2,288 | **Levels, 19 groups:** the stored depth was set in the order members were listed, so a member listed before its parent got its parent's depth as 0 (`VGRQXHF3J8VDLUA7XE92`: stored 2, computed 3); the computed depth walks the parent chain. **Members and late, 1 group:** BorgWarner Propulsion Systems (`254900XW4597ZTRCMN82`) was in two groups' fetch lists; its record names one group, so `549300DSFX2IE88NSX47` counts 36 members (stored 37) and 3 late (stored 4). **Members in GLEIF:** equal on every group (`GLEIF_GROUP_SIZE` is the figure the stored page held); 86 groups capped (stored 85: the group above now holds 36 of the 37 GLEIF lists) |
| `lei-country` | 133 countries + ALL | every count on every country | the stored page listed the first 400 entities of the six largest countries; the computed one lists all |
| `sic-sector` | 194 codes + ALL | companies, revenue and net income on every code (USD reporters); ALL 586 companies, 18,430,213,107,000 revenue, 2,340,128,705,000 net income | none |
| `country-group` | 12 groups | members on every group | the stored figures were the **World Bank's own aggregates** for 2025, which estimate members that have not reported; the computed ones add up the members, each at its latest year. GDP within 3% (ECG-EAS 32,711 against 33,657: three members' latest GDP is from 2022), population within 2.1%, growth within 0.32 points (ECG-MEA 3.06 against 2.75), inflation as the median within 0.5 points (ECG-LCN 2.58 against 2.08). The four income groups had no stored figures; they have computed ones now |
| `correlation-matrix` | 3 sets | the factors | the stored pairs were **synthetic**: drawn at random between 0.15 and 0.78 and labelled *2 years, Ledoit-Wolf*, with no price history behind them. Computed from the lake's 29 daily returns to 2026-09-30, Pearson: CORR-EU-EQ average 0.096 (stored 0.385), CORR-G10FX −0.012 (0.512), CORR-US-EQ 0.092 (0.406) |
| USEQ ratios | 586 companies | net margin on the 563 that report revenue and net income, and every year of the financials; revenue growth on 560 | **3 companies** whose previous fiscal year is missing from the financials (SNA, SWK and TXT: 2023, 2024, 2026): the stored growth compared with the last year reported (two years earlier); the computed growth is against the previous fiscal year only, and empty when it is missing |
| PXH analytics | live only | nothing was stored | new |
| FACT changes | 4,026 facts, 38,632 yearly rows | every change, on every row and every strip | none |
| IDX totals | 6 indices and lists | revenue, net income and divisions on all 6 (USD reporters, as stored) | the divisions are listed alphabetically, not largest first |
| `equity` `BETA` | 10 equities | none | the stored betas were **set by hand** (`tools/packgen/banking/data_names.py`), with no price series behind them; the demo's price walks are independent, so the computed betas over the 29 returns to 2026-09-30 are near zero (EQ-NVTK −0.07 against NDX, stored 1.28; EQ-GRNT 0.73 against UKX, stored 1.05; EQ-SLRS 0.32, stored 0.52). EQ-CSCA and EQ-LUMN name no index: no beta (stored 1.46 and 1.19) |

---

## Part III · How they are computed

### 19. The derived connector

`LEIG`, `LEIC`, `SIC` and `ECG` use the lightest mechanism Drishti has for a computed grouping: the **derived connector** (plugin
`derived`), the one the trading pack's `CTRD`, `PTRD`, `LTRD` and `ITRD` pages use. It reads every document of a kind through the router
(as of the business date asked), groups them by an expression and builds one page per group, once per date, shared by every reader.
Release 2.9.2 adds six settings to it, all generic:

```yaml
# config/packs/world-economy/pack.yaml (written by tools/packgen/economics/make.py)
macro-groupings:
  plugin: derived
  kinds: [country-group]
  settings:
    country-group:
      from: economy
      group-by: $.REGION_GROUP
      also-group-by: { income: $.INCOME_GROUP, world: "'ECG-WLD'" }   # an economy is in three groups
      id-field: GROUP_ID
      search-fields: NAME                                  # the type-ahead finds a group by its name
      members: MEMBER_ECONOMIES
      max-members: 100000                                  # no cap
      rows-field: ROWS
      fields:
        GDP_USD_BN: sum $.GDP_USD_BN
        INFLATION: median $.INFLATION                      # new aggregate: median
        GROWTH_WEIGHTED: 'sum $.GROWTH == null || $.GDP_USD_BN == null ? null : $.GDP_USD_BN * $.GROWTH'
        GROWTH_WEIGHT: 'sum $.GROWTH == null || $.GDP_USD_BN == null ? null : $.GDP_USD_BN'
      rows: { ECONOMY_ID: $.ECONOMY_ID, ECONOMY: $.NAME, GDP_USD_BN: $.GDP_USD_BN, GDP_PATH: $.GDP_PATH }
      then:                                                # over the page once its aggregates and rows are in
        GROWTH: '$.GROWTH_WEIGHT > 0 ? $.GROWTH_WEIGHTED / $.GROWTH_WEIGHT : null'
        GDP_PATH: group(flatten($.ROWS[*].GDP_PATH), 'YEAR', 'GDP')
        TOP_ECONOMIES: $.ROWS
      sort: { ROWS: GDP_USD_BN desc, TOP_ECONOMIES: GDP_USD_BN desc 10 }
```

| Setting | What it does |
|---|---|
| `also-group-by` | further group keys a member joins (a map of name → expression); a member is read once and counted in each group |
| `unlisted` | keys whose page keeps no member ids, rows or tree once its `then` fields are worked out (`ALL`: a page over every member) |
| `median` | a new aggregate beside `count`, `sum`, `avg`, `min`, `max`, `distinct` and `first` |
| `then` | fields worked out over the page (its aggregates, its rows, `$.id`) with Rachana-EL: ratios, weighted averages, picks, `group(...)` counts. Each reads the page as the aggregates and rows left it, not another `then` field; a field that fails is empty |
| `sort` | a list field in order: `COLUMN [asc|desc] [N]`; rows without the column go last |
| `tree` | `{ id: LEI, parent: PARENT }`: each row's `LEVEL` (the key 0, a parent outside the group 1 below the key), the nested `TREE` (`CHILDREN`) and the deepest `LEVELS` |
| `search-fields` | page fields a search matches besides the id; the first labels the hit |

The order on a page: the aggregates, the rows, the tree, the `then` fields, the sorts, then `unlisted`. Pages live in the heap by default
(`store: disk` keeps them on disk for very large kinds, as the trading pages do). Across Drishti*net nodes a group is gathered from every
node's share ([CLUSTER_DEPLOYMENT.md](../admin/CLUSTER_DEPLOYMENT.md#1-drishtinet-many-servers-one-drishti)); a median cannot be combined from parts, so a group whose members are split
over nodes without every member listed shows no median.

The code: `drishti-engine/.../source/derived/DerivedKind.java` (grouping, aggregates), `DerivedPage.java` (`then`, `sort`, `tree`),
`DerivedSourcePlugin.java` (reading, caching, search), `GroupShares.java` (Drishti*net).

### 20. The correlation engine

`CORR` is not a grouping: it needs statistics over aligned time series, which a derived page cannot do. It is a **reactive connector**
with a Java engine in the server's own process (the pattern of VaR's `var-result`, [VAR_GUIDE.md Part III](VAR_GUIDE.md#24-where-the-engine-lives)):

```yaml
# config/packs/market-data/pack.yaml (written by tools/packgen/banking/make_packs.py from corr_kinds.py)
correlation-engine:
  plugin: reactive
  kinds:
    correlation-result:
      compute: correlation
      params: { window: { type: int, default: 0, min: 0, max: 5000 }, estimator: { type: enum, options: [pearson, ledoit-wolf], default: pearson } }
  reads:
    - { kind: correlation-matrix, as: set, id: "{id}" }
    - { kind: equity, as: equities, ids: "{inputs.set.EQUITY_IDS}", limit: 200 }
    - { kind: fx-spot, as: fx, ids: "{inputs.set.FX_IDS}", limit: 200 }
  engine: { type: java, class: com.ash.drishti.compute.reactive.analytics.CorrelationEngine }
  triggers: [{ on: data-version, kinds: [correlation-matrix, equity, fx-spot], affects: all }]
```

Every input is read **as the person asking** (their entitlements and masks apply before a number reaches the engine); an answer is cached
per set, parameters, date and view signature, and recomputed when the inputs load. No compute pool is needed: the engine runs in the
server even with `DRISHTI_COMPUTE_ENABLED=false`. The code is `drishti-compute/.../reactive/analytics/CorrelationEngine.java`; the
`correlation-matrix` Sutra is hand-written (`tools/packgen/banking/make_sutras.py`, `HAND_WRITTEN`).

### 21. The price analytics

PXH's prices are read live, never stored; the analytics are worked out in the same step, by the `yfinance` plugin
(`plugins/drishti-plugin-yfinance/.../PriceAnalytics.java`), from the history it has just parsed, and the benchmark's history read
through the same cache. The connector `us-equities-prices` is **off as shipped** and **internal use only**: switching it on sends each
ticker opened to Yahoo Finance, and the prices and everything computed from them may not be redistributed.

### 22. Where the inputs come from

| Computed | Inputs | External data | Refresh script |
|---|---|---|---|
| LEIG, LEIC | `lei-entity`: `LEI_GROUP`, `DIRECT_PARENT`, `LEI_COUNTRY`, `RENEWAL_LATE`, `REGISTRATION_STATUS`, `GLEIF_GROUP_SIZE`, ... | GLEIF golden copy level 1 and 2, LEI API, mapping files (CC0) | `tools/publicdata/gleif.py refresh` (`gleif.sh`, `publicdata-gleif.ps1`) → `tools/packgen/gleif/make.py` |
| SIC, USEQ ratios | `us-company`: `SIC`, `SIC_DESCRIPTION`, `CURRENCY`, `REVENUE`, `NET_INCOME`, `FISCAL_YEAR`, `FINANCIALS[]` | SEC EDGAR submissions and XBRL company facts (public domain) | `tools/publicdata/us_equities.py refresh` (`us-equities.sh`) → `tools/packgen/us_equities/make.py` |
| ECG | `economy`: `REGION_GROUP`, `INCOME_GROUP`, `GDP_USD_BN`, `POPULATION_M`, `GROWTH`, `INFLATION`, `YEAR`, `GDP_PATH` | World Bank WDI (CC BY 4.0), IMF WEO (internal demonstrations only), CIA Factbook | `tools/publicdata/world_economy.py` (`world-economy.sh`) → `tools/realdata/realdata.py` → `tools/packgen/world/make.py` |
| CORR | `correlation-matrix` (the factors), `equity` and `fx-spot` `HISTORY[]` | none: the market-data pack's synthetic data | `tools/packgen/banking/make_data.py`, `tools/testdata/make_testdata.py` |
| PXH analytics | the price history read live | Yahoo Finance's public chart endpoint (internal use only) | none: live, off as shipped |
| FACT changes, IDX totals | `financial-fact` `HISTORY[]`, `index-membership` `ROWS[]` | SEC EDGAR, Wikidata | `tools/publicdata/us_equities.py` |
| EQB | `equity` and `equity-index` `HISTORY[]` | none: synthetic | `tools/packgen/banking/make_data.py`, `tools/testdata/make_testdata.py` |

The refresh scripts **no longer write any computed figure**: the gleif generator writes no `lei-group` or `lei-country` snapshot and an
ultimate parent carries `GLEIF_GROUP_SIZE` (its group's size in GLEIF, an input the page reads); the us-equities generator writes no
`sic-sector` snapshot and no ratio; `tools/realdata/build_docs.py` builds no country group; the market-data generator writes the
correlation sets' factors only. `tools/testdata/publicsnap.py` lists the kinds it writes, and `COMPUTED` the ones it never writes again.

### 23. The retirement guard

`tools/contracts.policy.yaml` records the decision, and `tools/test_contracts.py` fails the build if it is undone:

- `computed-in-place:` `lei-group`, `lei-country`, `sic-sector`, `country-group` (2.9.2): each is routed to a derived connector, no Delta
  connector serves or lays it out, no sample, test data or test-data folder holds it, no public-data generator writes it, and its contract
  is class `computed` over plugin `derived`;
- `removed:` `us-company` `NET_MARGIN`, `REVENUE_GROWTH`, `FINANCIALS[].NET_MARGIN`; `index-membership` `ROWS[].NET_MARGIN`,
  `SECTORS`, `REVENUE`, `NET_INCOME`, `BY_SECTOR`; `financial-fact` `CHANGE_PCT`, `HISTORY[].CHANGE_PCT`; `correlation-matrix` `AVERAGE`,
  `PAIRS`, `WINDOW`, `ESTIMATOR`; `equity` `BETA`: no record or contract carries them again;
- `engine-columns: correlation` (`RHO`, `SHRINKAGE`, `MIN_RHO`, `MAX_RHO`, `FACTOR_STATS`): no fundamental or input kind carries one;
- `classes: computed` lists the six computed kinds; the CORR and EQB tests check the engine classes exist and every set names its factors.

### 24. Settings and limits

| Setting | Default | Where |
|---|---|---|
| `DRISHTI_LAKE_ENABLED` | `true` | switches the derived connectors and the correlation engine with the lake they read |
| a derived connector's `refresh` | `60s` | how often a date's pages are rebuilt when the data's version cannot be asked |
| `max-scan` | `50000` | the most documents a derived connector reads of a kind (9,937 entities, 586 companies, 213 economies) |
| `DRISHTI_US_EQUITIES_PRICES_ENABLED` | `false` | PXH and its analytics |
| `DRISHTI_US_EQUITIES_BENCHMARK` | `SPY` | the symbol PXH's beta is measured against |
| `correlation-engine` `limits` | timeout 30 s, wait 5 s, 4 in flight a user | as every reactive connector ([DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#4-reactive-connectors)) |

Memory: a derived page set holds each page's aggregates and rows; the gleif pages of the committed data (590 groups, 133 countries) are a
few megabytes, built in about a second from the 9,937 entities.

## Part VIII. Changing Drishti's code: the developer guide

This is the handbook for changing Drishti's code. It explains how the repository is laid out, how to build, run and test it from a
fresh clone, which rules the build enforces, how a request (a view, a BI question, a live tick) travels through the code, and then
gives worked recipes for the changes people make most often. Every class, path and command here exists in the repository (checked
against this release on a fresh clone); the recipes follow the idioms of the real classes they imitate.

If you only want to run Drishti, read [GETTING_STARTED.md](GETTING_STARTED.md); to run it from IntelliJ IDEA and PyCharm, read
[GETTING_STARTED.md (IDE)](GETTING_STARTED.md#running-from-intellij-idea-and-pycharm). For the design and its reasons, read [ARCHITECTURE.md](../architecture/ARCHITECTURE.md), the BI model
architecture [BI_DESIGN.md section 14](../architecture/BI_DESIGN.md#14-the-model-architecture-decisions-d1-to-d6) and the [decision records](../architecture/adr/README.md).

---

### 1. The repository

Drishti has two programs. The **server** is one Spring Boot 3.5 application on Java 21 or newer, production runs 21
(`drishti-server`), built from a Maven reactor of internal modules; it reads every source, lays out every screen, answers every BI
question and pushes every live change. The **console** is a FastAPI and Jinja2 web application in `drishti-console/` that renders the
server's JSON; the browser draws what it is sent and computes no number of its own (the BI rule *the servers compute, the browser
draws*, [BI_DESIGN.md section 14.5](../architecture/BI_DESIGN.md#145-the-servers-compute-the-browser-draws)). Industries are **packs** (`config/packs/`),
which are content and configuration, not code: seven ship (`core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`,
`world-economy`, `news-and-markets`), every attribute of their documents in UPPER_SNAKE.

#### 1.1 Java modules

All Java code lives under the package `com.ash.drishti`. The parent POM (`pom.xml`, artifact `drishti-parent`) inherits from
`spring-boot-starter-parent`, which aligns library versions, so there is no separate BOM module.

| Module | Package | What it owns | Key classes |
|---|---|---|---|
| `drishti-api` | `api` | The plugin SPI and data model. **No Spring**, no internal dependencies. | `SourcePlugin`, `PluginManifest`, `SourceCapabilities`, `SourceContext`, `EntityRef`, `EntityDocument`, `Provenance`, `AsOf`, `DataNode`, `HitIndex`, `PluginNotConfigured` |
| `drishti-common` | `common` | Error codes, the exception type, JSON, shape fingerprints, business calendars, branding, a pack's samples (`PackSamples`), retries and live settings. | `ErrorCode`, `DrishtiException`, `JsonCodec`, `ShapeFingerprinter`, `BusinessCalendar`, `PackSamples`, `RetryPolicy`, `RetryBoard`, `LiveSettings` |
| `drishti-rachana` | `rachana` | The Rachana grammar: the Sutra model, the YAML parser (`*.sutra.yaml`, `rachana: 1`), the registry with hot reload, the JSON Schema of the language, Rachana-EL, formats and tones. | `SutraRegistry`, `SutraMatcher`, `parse.SutraParser`, `parse.SutraBuilder`, `RachanaSchema`, `model.PanelKind`, `el.ElCompiler`, `el.Functions`, `format.Formats` |
| `drishti-inference` | `inference` | Layout inference: semantic hints, rules, the merge of a Sutra with inferred panels. | `InferenceEngine`, `Rules`, `Semantics`, `LayoutMerger`, `EffectiveLayout` |
| `drishti-graph` | `graph` | Entity references: which fields link to which kinds, and link badges. | `ReferenceCatalog`, `BadgeRenderer`, `GraphProperties` |
| `drishti-engine` | `engine` | Sources and routing (including a **union** of connectors that serve one kind together), the view pipeline, binding, the ViewModel, commands and type-ahead, search, history, impact, business dates, live topics. | `ViewPipeline`, `source.SourceRegistry`, `source.SourceRouter`, `source.ConnectorInstance`, `bind.Binder`, `view.ViewModel`, `view.PanelData`, `live.TopicHub`, `live.ViewStream`, `command.CommandParser`, `search.StructuredSearch` |
| `drishti-identity` | `identity` | Users, passwords, roles, lockout, preferences, pack switches and the audit trail, in SQLite or PostgreSQL through JPA. | `UserService`, `JpaUserStore`, `RoleStore`, `PackStateStore`, `JpaAuditLog`, `IdentityDatabase`, `db.IdentityRepositories` |
| `drishti-packs` | `packs` | Loads the enabled packs and turns them into the lowest-precedence property source; writes connector files from the packs' templates. | `PackLoader`, `PackLineage`, `PackEnvironmentPostProcessor` (registered in `META-INF/spring.factories`), `ConnectorBootstrap` |
| `drishti-ai` | `ai` | The LLM provider layer and the MCP client: providers (Anthropic, OpenAI-compatible through Spring AI, a mock), model registry, call policy, prompt masking, MCP clients over streamable HTTP. Depends on `api` and `common` only (`ArchitectureRulesTest.aiLayerDependsOneWay`). **Off by default**; see [AI.md](../admin/AI.md#1-model-providers) and [AI.md](../admin/AI.md#2-the-mcp-client-other-servers-tools-behind-drishtis-policy). | `LlmGateway`, `LlmRegistry`, `LlmProvider`, `AnthropicProvider`, `SpringAiOpenAiProvider`, `McpGateway`, `McpRegistry`, `McpClient` |
| `drishti-diskcache` | `diskcache` | A disk-backed, size-bounded, self-clearing cache (RocksDB) for live connector state. | `DiskCache` |
| `drishti-messaging` | `messaging` | The latest-state store and live fan-out shared by the ActiveMQ and RabbitMQ connectors. | `MessageStateSource` |
| `drishti-lakeio` | `lakeio` | Lake file access shared by the native Delta and Iceberg engines, no Hadoop: `Storage` (local disk through `java.nio`, Windows drive letters, backslashes and UNC shares included; S3 through the AWS SDK, with conditional PUTs for commits), byte ranges, Parquet page codecs (`NativeCodecs`, reading and writing) and the per-file Parquet footer cache. | `Storage`, `LocalStorage`, `S3Storage`, `LocalPaths`, `NativeCodecs`, `ParquetFooters` |
| `drishti-iceberg` | `iceberg` | Drishti's own Apache Iceberg engine without Hadoop, on `drishti-lakeio`: metadata JSON kept whole, path-based, metadata-file and REST catalogs (JDK HTTP client, OAuth2, ETag, vended credentials, SigV4), Avro manifests, planning with partition and metric pruning, field-id Parquet reads with schema evolution and deletes, the Parquet writer with Iceberg metrics, safe commits and the table operations. Its tests prove the writer loads no Hadoop `Configuration`. | `Table`, `catalog.Catalogs`, `scan.Planner`, `read.TaskReader`, `write.Operations`, `write.ParquetDataWriter` |
| `drishti-deltalake` | `deltalake` | A Delta Kernel engine without Hadoop on `drishti-lakeio` (local disk, Windows paths included, and S3; Parquet through parquet-java with the shared input files and codecs); and Drishti's own Delta writer (`lake build --engine drishti`). Its tests prove in an isolated class loader that no Hadoop `FileSystem`, `Shell` or `Configuration` loads. | `NativeEngine`, `write.LakeManifest` |
| `drishti-lakeload` | `lakeload` | The parallel lake loader (2.9.6): JSON Lines from a folder or S3 into Delta or Iceberg, readers, parsers and writers on every core, memory bounded by `memory.budget-mb` (sorted runs spilled to disk), one commit per business date ([PARALLEL_LOADER.md](../architecture/PARALLEL_LOADER.md)). | `LakeLoad`, `Loader`, `RowParser`, `Runs`, `DeltaTarget`, `IcebergTarget` |
| `drishti-testkit` | `testkit` | Contract tests every source plugin of a kind must pass. | `DatedSourceContract`, `MessageSourceContract`, `BrokerOutage` |
| `drishti-server` | `server` | The Spring Boot application: REST and SSE controllers, security, alerts, Sutra governance, and one sub-package per feature area (`admin`, `ai`, `alerts`, `bi`, `calc`, `cli`, `collab`, `connectors`, `deploy`, `design`, `dist`, `embed`, `explain`, `governance`, `layout`, `loads`, `notify`, `pivot`, `registry`, `reports`). `bi` is Rūpaka, the BI engine (section 4.4). | `DrishtiApplication`, `api.*Controller`, `security.Entitlements`, `security.TokenFilter`, `alerts.AlertEngine`, `governance.SutraGovernance`, `bi.mq.MqService`, `bi.engine.QueryService` |
| `drishti-it` | `it` | Repository-wide rules, as tests only. | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest`, `NoBlockingUnderSynchronizedTest` |
| `drishti-benchmarks` | `benchmarks` | JMH benchmarks of the hot paths (fingerprint, Rachana-EL, formats, cold inference). | `HotPathBenchmark` |

#### 1.2 Source plugins

Each plugin is its own module under `plugins/`, depends on `drishti-api` only (plus `drishti-common` and `drishti-testkit` for
tests), and registers itself in `src/main/resources/META-INF/services/com.ash.drishti.api.SourcePlugin`. The server depends on all of
them, so they are on its class path; each starts only when enabled or used by a named connector, one YAML file per connector in
`config/connectors/` ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md)).

| Module | Plugin name | Reads | Extra internal dependency |
|---|---|---|---|
| `drishti-plugin-demo` | `demo` | The enabled packs' `samples/` (`<kind>.jsonl`); random-walk ticks ([DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md)) | — |
| `drishti-plugin-file` | `file` | JSON or JSON Lines documents in a folder, dated by folder | — |
| `drishti-plugin-rest` | `rest` | An HTTP/JSON service | — |
| `drishti-plugin-jdbc` | `jdbc` | A database by query, or the PostgreSQL entity-table mode | — |
| `drishti-plugin-delta` | `delta` | Delta Lake tables through Delta Kernel (no Spark), with time travel; engine `native` (no Hadoop) or `hadoop`; the demo lakes and every shipped pack's store | `drishti-deltalake` |
| `drishti-plugin-aerospike` | `aerospike` | A set per data domain: a record per entity per business date with promoted bins, an index record per entity, a record per kind (`AerospikeLayout`); `AerospikeLoader` writes it | — |
| `drishti-plugin-cassandra` | `cassandra` | Apache Cassandra, ScyllaDB, Astra: a keyspace per data domain, a partition per business day, generation and bucket keyed by id, `entity_days` and `kind_days` (`CassandraLayout`); `CassandraLoader` writes it, a day under a new generation | — |
| `drishti-plugin-redis` | `redis` | Redis (standalone or Cluster): today and recent days in memory, with live updates | — |
| `drishti-plugin-mongodb` | `mongodb` | A document per entity per business day, in a collection per data domain | — |
| `drishti-plugin-iceberg` | `iceberg` | Apache Iceberg tables in Drishti's layout (path-based or a REST catalog), with time travel; engine `native` (drishti-iceberg, no Hadoop) or `library` | `drishti-iceberg` |
| `drishti-plugin-iceberg-external` | `iceberg-external` | Iceberg tables other engines write, rows as documents, read-only, through drishti-iceberg | `drishti-iceberg` |
| `drishti-plugin-duckdb` | `duckdb` | Every data domain in one embedded DuckDB file | — |
| `drishti-plugin-feeds` | `feed` | Public feeds: NY Fed SOFR, ECB €STR and FX, US Treasury, FRED ([FEEDS_CONNECTOR.md](../connectors/FEEDS_CONNECTOR.md)) | — |
| `drishti-plugin-news` | `news` | Headlines for a named entity, computed on demand from Google News RSS (code, not a store) | `drishti-common` |
| `drishti-plugin-yfinance` | `yfinance` | A quote and price history for a ticker, computed on demand from Yahoo Finance | `drishti-common` |
| `drishti-plugin-kafka` | `kafka` | Compacted topics of entity documents | `drishti-diskcache` |
| `drishti-plugin-activemq` | `activemq` | ActiveMQ queues and topics | `drishti-messaging` |
| `drishti-plugin-rabbitmq` | `rabbitmq` | RabbitMQ queues | `drishti-messaging` |
| `drishti-plugin-s3` | `s3` | Documents in Amazon S3 or an S3-compatible store | — |

Besides these plugins, the engine has one built-in plugin type, `derived`: entities computed from other kinds (the trading
pack's `trade-pages` connector makes the counterparty, portfolio, legal-entity and index pages from the `trade` kind).

#### 1.3 How the modules depend on each other

Compile-scope dependencies, from the POMs:

```text
drishti-api                       (nothing internal; no Spring)
└── drishti-common
    ├── drishti-ai                 (api, common; Spring AI)
    ├── drishti-rachana
    │   ├── drishti-inference ──┐
    │   └── drishti-graph ──────┴── drishti-engine
    ├── drishti-identity
    ├── drishti-packs
    └── drishti-testkit            (api, common)
drishti-diskcache                 (nothing internal)
drishti-messaging                 (api, diskcache)
drishti-lakeio                    (api)
drishti-deltalake                 (lakeio)
drishti-iceberg                   (api, lakeio)
drishti-lakeload                  (api, lakeio, deltalake, iceberg)
plugins/*                         (api; kafka adds diskcache; activemq and rabbitmq add messaging; delta adds deltalake; iceberg and iceberg-external add iceberg)
drishti-server                    (engine, identity, packs, ai, lakeload, and all nineteen plugins)
drishti-it                        (server, testkit)
drishti-benchmarks                (inference)
```

`ArchitectureRulesTest` keeps these one-way (see [§3.3](#33-architecture-rules)): nothing below the engine may see `engine` or
`server`, the engine may not see `server`, `api` sees nothing internal, and `ai` sees only `api` and `common`. The reactor also builds
`docs/guides/examples/connector`, the worked example plugin of [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md).

#### 1.4 Everything else

| Folder | What is in it |
|---|---|
| `drishti-console/` | The web console: `run_drishti_web.py` (entry point), `core/` (app factory, backend client, auth, business date, packs, config loader, `ai/` for the console's LLM clients, and one module per feature such as `forms.py`, `bi.py`, `controls.py`, `calc.py`, `retry.py`), `routes/` (one router per area), `web/templates/` (Jinja2, with `_macros/panels.html` holding one macro per panel kind), `web/embed/` and `web/elements/` (Drishti Elements, the `<drishti-view>` web component), `web/static/` (`js/`, `css/`, `img/`, and third-party code under `vendor/`), `web/guides/` (in-app guides), `config/` (`application.yaml`, `help.yaml`, `workspaces.yaml`, `competitive.yaml`), `tests/` (pytest, with a fake backend in `conftest.py`; 255 files, 89 of them Chromium tests named `*_browser.py`) |
| `config/packs/<name>/` | One domain pack: `pack.yaml`, `sutras/`, `samples/` (`catalog.jsonl` and one `<kind>.jsonl` per kind), `config/` (formats, semantics, help, workspaces), `guides/`, `python/` (Calc snippets) and `tests/`. Seven ship: `core`, `market-data`, `trading`, `counterparty-risk`, `market-risk`, `world-economy`, `news-and-markets`. The trading pack's Sutras are organised `sutras/common/` (shared blocks), `sutras/endur/`, `sutras/calypso/`, `sutras/murex/` (one per product type) and `sutras/pages/` (cross-system pages); the folders are organisation only: each Sutra's `match` (`SOURCE_SYSTEM` and `PRODUCT_TYPE`) decides ([SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md)) |
| `examples/packs/` | The retired packs (`finance`, `genomics`, `logistics`, `retail-banking`, `climate-risk`, `operational-risk`, `liquidity-risk`), kept as examples and as fixtures for tests written against them; loaded only with `DRISHTI_PACKS_EXTRA_DIRS=examples/packs` |
| `config/connectors/`, `config/connectors.examples/` | The site's connector files (written by the server at its first start from the packs' templates; not committed) and ready-made examples per store ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)) |
| `config/bi/` | Shipped BI files: datasets, models, reports, boards |
| `run-configs/` | The IntelliJ IDEA run configurations (IntelliJ opens the repository root; [GETTING_STARTED.md (IDE)](GETTING_STARTED.md#running-from-intellij-idea-and-pycharm)): **Drishti build server jar**, **Drishti server**, **Drishti server (demo lakes)**, **Drishti server (debug-friendly)**, **Drishti server (QA profile)**. The PyCharm ones (PyCharm opens `drishti-console/`, so the two IDEs never share one `.idea`) are in `drishti-console/run-configs/`: **Drishti console**, **Build demo lakes**, **Load ENDUR file into the lake**, the QA console and the CLI tools |
| `data/testdata/` | The committed test data: sixty business days of every dataset the seven packs read (5,000 trades a day per source system), `.jsonl.gz`, with `testdata.yaml` and a README ([data/testdata/README.md](../../data/testdata/README.md)) |
| `tools/drishti.py` | The one command line over the tools below and the server's REST: `sutra`, `pack ...` (`new`, `make`, `check`, `about-check`, `diff`, `i18n`, `publish`, `install`, `regenerate`, `catalogue`, `bundle`, `verify`, `deploy`, `rollback`, `keygen`), `data ingest|load|profile`, `server health|packs|smoke`, `connector ...`, `bi ...`, `view get|explain`, `design ...`, `lake build|publish|scan|status|demo|maintain`, `doctor`, `completion`, `docs shots`; the `test_cli_*.py` and `test_drishti_cli.py` suites cover it. See [CLI_GUIDE.md](CLI_GUIDE.md) and [TOOLS.md](TOOLS.md) |
| `tools/lake/` | Delta lakes from Python: `build.py` (`lake build`: a large JSON export streamed into a Delta table, flat memory, partitions, promoted columns, the data-load signal or the BI-store stamp), `demo.py` (`lake demo`: the demo lakes from `data/testdata`), `scan.py`, `laketables.py` (`lake status`), `maintain.py` (retention, compaction, checkpoints, vacuum, `relayout`), each with its tests |
| `tools/testdata/` | `make_testdata.py` (writes `data/testdata`, deterministic, `--check`), `universe.py` (the fictitious counterparties, books and legal entities), `test_testdata.py`, `test_deterministic.py` (the same bytes on every Python) |
| `tools/packgen/` | Pack generators: `banking/` (the five banking packs from one taxonomy, `make_data.py` for their samples and `--lake`/`--jsonl`), `trades/` (the common trade schema every source system writes, `schema.py`, and the ENDUR, CALYPSO and MUREX generators), `common/` (`packbuild.py`, `packsamples.py`, `walks.py`, `upper.py`), `world/`, `economics/`, `politics/`, and the retired packs' generators |
| `tools/samplegen/` | Sample-history helpers: `lake.py` (a pack's sample history as JSONL loader rows), `load_rows.py` (loads such rows into Delta), `layout.py` (the pack-declared lake layout: promoted columns, sorted files), `bulk_trades.py` (a large trading book for one source system: `--trades`, `--days`, `--system`), `stream.py` (Kafka ticker), `dates.py` (business-day calendars) |
| `tools/load-*.sh` | Load the samples (and a generated book) into PostgreSQL, Iceberg, DuckDB, MongoDB, Redis, Aerospike, Cassandra, JSON-lines files or Delta ([DEMO_DATA.md](../connectors/DEMO_DATA.md)) |
| `tools/bench/` | The scale benchmark: `scale.sh <store> <trades-a-day>`, `measure.py`, `report.py`; `results/` keeps each run's raw JSON lines; see [SCALE_BENCHMARK.md](../admin/SCALE_BENCHMARK.md) |
| `tools/packgen/pack_from_jsonl.py`, `tools/packmake.py`, `tools/schemapack.py`, `tools/packbundle.py`, `tools/packform.py`, `tools/packdiff.py`, `tools/packi18n.py`, `tools/packregen.py`, `tools/sutragen.py`, `tools/authoring.py` | Pack authoring: a pack from JSON lines or from schemas, regeneration of unedited generated files, bundles, the `form:` section, `pack diff`, `pack i18n`; the `tools/drishti.py` commands call them (see this document, [DATA_GUIDE.md](DATA_GUIDE.md#5-schema-to-pack-generate-a-pack-from-json-schema), [CLI_GUIDE.md](CLI_GUIDE.md)) |
| `tools/realdata/`, `tools/docs/`, `tools/elements-demo/`, `tools/windows/`, `tools/maps/` | The real-data builds of the world-economy pack ([DATA_GUIDE.md (world-economy)](DATA_GUIDE.md#the-world-economy-data-in-detail)); the documentation screenshots (`tools/docs/screenshots.py`, `shots/`, one module per guide and tutorial); a sample host page for Drishti Elements; the PowerShell starters ([GETTING_STARTED.md (Windows)](GETTING_STARTED.md#drishti-on-windows)); the map region builder |
| `tools/packreg/` | `packreg.py`: signing keys, publishing packs to a signed registry, verifying one (ADR-018); `test_packreg.py` |
| `tools/` (files) | `drill.sh` (verify and publish), `fetch-pyodide.sh` (installs Calc's Python runtime, pinned and verified, into `drishti-console/web/static/vendor/pyodide/`), `elements_sheet.py` (regenerates the Elements stylesheet), `make_icons.py` (favicon and app icons), `ingest_jsonl.py` and `ingest_watch.py` (`data ingest`), `serverpacks.py`, `serverconnectors.py`, `doctor.py`, `completion.py`, `viewcmd.py`, `dataprofile.py`, `license_headers.py` (check or insert the copyright header), `test_no_hidden_names.py` (no hidden names, section 8), `rachana/md_to_yaml.py` (converts Markdown Sutras read before 1.11 to `*.sutra.yaml`) |
| `deploy/` | `server.Dockerfile`, `console.Dockerfile`, `compose.yaml`, `compose.data.yaml`, `lake-maintenance.yaml`, `grafana/` |
| `config/license-header.txt` | The text of the copyright header that `license_headers.py` inserts |
| `data/` | The **data folder** (`DRISHTI_DATA_DIR`): everything Drishti writes at run time, all git-ignored except `testdata/`: `delta/` (the demo lakes), `bi-store/` (BI columns and rollups), `connectors/` (`generated/` and `history/`), `identity/`, `feeds/`, `packs/` (`added.yaml`, installed archives), `loads/`, `governance/`, `collab/`, `reports/`, `qa/` (the QA profile), `build/` (tool output and staging), `drill/` (the drill's logs) |
| `docs/` | These documents, by audience: `guides/` (getting started, user, developer, API, Rachana, BI and troubleshooting guides), `tutorials/` (18 hands-on tutorials), `connectors/` (the connector and plugin guides, a design document per store, demo data), `admin/` (operations, configuration, users, performance, and the runbooks under `admin/runbooks/`), `architecture/` (architecture, design notes and the ADRs under `architecture/adr/`), `qa/`; `README.md` is the index; reference mockups and logos under `requirements/` |
| `.github/workflows/fast.yml`, `windows.yml` | CI: `fast.yml` runs `./mvnw -B -q verify` and the header check on Java 21 and 25 and `pytest` for the console on Python 3.13; `windows.yml` builds on Windows, runs the native Delta engine, the Delta connector and server tests, and asks a server on a small lake for a view, a search and a past date |

---

### 2. Building and running from source

#### 2.1 Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 or newer (any vendor; Java 21 is the production target) | The enforcer rule in `pom.xml` (`requireJavaVersion [21,)`) stops an older Java with `Drishti needs Java 21 or newer (any vendor; production runs Java 21). Maven is running on Java …` The bytecode targets 21 (`maven.compiler.release`), so one jar runs on 21 and 25; `.java-version` says `25`, the version to develop on. On Java 25 run the server with `-XX:+UseCompactObjectHeaders` (about 10% less heap); Java 21 has no such flag and refuses to start with it. Java 21 pins a virtual thread's carrier while it blocks inside `synchronized`, so main code uses a `ReentrantLock` for anything that does I/O (`NoBlockingUnderSynchronizedTest` enforces it; see [PERFORMANCE.md](../admin/PERFORMANCE.md#java-21-and-virtual-thread-pinning)). |
| Python | 3.13 ([the minimum and why](GETTING_STARTED.md#step-1--install-the-prerequisites)) | The console and the tools. CI uses 3.13. |
| uv | any recent | Creates the console's environment (`uv venv --python 3.13`), which works where the system Python has no `ensurepip` (Debian and Ubuntu without `python3.X-venv`); runs some tools with `uv run --with …` so nothing is installed globally. |
| Docker | optional | Only for the Testcontainers tests (PostgreSQL, Aerospike, Cassandra, ScyllaDB, ActiveMQ, RabbitMQ, MinIO) and `deploy/docker/compose.yaml`. |

Set `JAVA_HOME` in every terminal:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v
```

You should see `Java version: 21…` in the output. Maven itself comes with the repository (`./mvnw`, Maven 3.9.12).

**From a fresh clone to a running stack** (what [GETTING_STARTED.md](GETTING_STARTED.md) and
[data/testdata/README.md](../../data/testdata/README.md) walk through; measured on a 24-core laptop):

```bash
git clone https://github.com/ajsinha/drishti.git && cd drishti
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt
./mvnw -q package -DskipTests -pl drishti-server -am                 # 1 min 12 s with ~/.m2 filled
drishti-console/.venv/bin/python tools/drishti.py lake demo           # about 15 min (943 s): 85 Delta tables, sixty days, under data/delta
```

then the server and the console as the run configurations start them ([GETTING_STARTED.md, Steps 6 and 7](GETTING_STARTED.md#step-6--start-the-server)).

#### 2.2 Maven commands

Run them from the repository root.

| You want to… | Command |
|---|---|
| Build everything, no tests | `./mvnw -q package -DskipTests` |
| Build only the server and what it needs | `./mvnw -q package -DskipTests -pl drishti-server -am` |
| Build and run every test and rule | `./mvnw -q verify` |
| The same, without touching the network (what the drill runs) | `./mvnw -q -o verify` |
| Build one module and what it needs | `./mvnw -q -pl drishti-engine -am package -DskipTests` |
| Run one module's tests | `./mvnw -q -o -pl drishti-rachana -am test` |
| Run one test class | `./mvnw -q -o -pl drishti-server -am test -Dtest=ViewPipelineTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Run one test method | `./mvnw -q -o -pl drishti-rachana -am test -Dtest='ElTest#pathsOperatorsAndFunctions' -Dsurefire.failIfNoSpecifiedTests=false` |
| Run only the repository rules | `./mvnw -q -o -pl drishti-it -am verify -Dtest='LicenseHeaderTest,SourceFileSizeTest,ArchitectureRulesTest' -Dsurefire.failIfNoSpecifiedTests=false` |
| Format before committing | `./mvnw -o -q spotless:apply -pl <module>` |

What the flags mean:

- `-q` quiet: a successful build prints little or nothing.
- `-o` offline: use only what is already in `~/.m2`. It fails on a fresh machine; run once without `-o` first.
- `-pl <module>` build only that module; `-am` ("also make") builds the modules it depends on first. Without `-am`, Maven takes the
  upstream modules from `~/.m2`, which may be stale.
- `-DskipTests` compiles tests but does not run them.
- `-Dtest=…` selects tests; `-Dsurefire.failIfNoSpecifiedTests=false` stops the upstream modules that `-am` pulls in from failing
  because they hold no such test.

The server jar is `drishti-server/target/drishti-server-<version>-exec.jar` (the `exec` classifier is set on the
`spring-boot-maven-plugin` in `drishti-server/pom.xml`). The plain `drishti-server-<version>.jar` beside it is the library jar, not the
application.

> **Note:** tests read the same `${ENV:default}` placeholders as the running server. Run Maven in a shell where `DRISHTI_PACKS`,
> `DRISHTI_SECURITY_ENABLED` and the other `DRISHTI_*` variables are **not** set (the run configurations set them only for the server
> they start). The server's tests set their own packs in `drishti-server/pom.xml` (`drishti.packs.extra-dirs` is `examples/packs` and
> `drishti.packs.enabled` is `finance`, so the tests written against the retired `finance` pack, such as `ApiTest` with its
> `IRS-48213`, keep it as a fixture).

#### 2.3 Tests that need Docker

Tests annotated `@Testcontainers(disabledWithoutDocker = true)` start real services in Docker and are **skipped** (not failed) when
Docker is not reachable:

| Test | Starts |
|---|---|
| `drishti-identity` `PostgresIdentityStoreTest` | PostgreSQL 18 |
| `drishti-plugin-jdbc` `PostgresEntityTableTest`, `PostgresReconnectTest` | PostgreSQL |
| `drishti-plugin-aerospike` `AerospikeSourcePluginTest` | Aerospike Community Edition |
| `drishti-plugin-cassandra` (every test class but `CassandraLayoutTest`) | one Cassandra 5 node shared by the module's classes, ScyllaDB for `ScyllaSourcePluginTest`, a TLS node for `CassandraTlsTest` |
| `drishti-plugin-activemq` `ActiveMqSourcePluginTest`, `ActiveMqOutageTest` | ActiveMQ |
| `drishti-plugin-rabbitmq` `RabbitMqSourcePluginTest`, `RabbitMqOutageTest` | RabbitMQ |
| `drishti-plugin-delta` `DeltaOnS3Test` | an S3-compatible store |

`DeltaAfterMaintenanceTest` instead assumes `uv` is installed (it runs `tools/lake/maintain.py`) and is skipped otherwise. The Kafka
tests use an embedded broker from `spring-kafka-test` and need no Docker. The tests never use a fixed port: Testcontainers and the
in-test servers take free ones, so they run beside a Drishti, a PostgreSQL or a Kafka you already run.

A green build on a machine without Docker has therefore not run the PostgreSQL schema check. Run the identity tests on a machine with
Docker before you merge a schema change ([§5.6](#56-add-a-table-to-the-identity-database)).

#### 2.4 The console from source

```bash
uv venv --python 3.13 drishti-console/.venv
uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements-test.txt   # requirements.txt, plus pytest-xdist, Playwright and the test extras
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

You should see `Uvicorn running on http://0.0.0.0:17480` (`server.host` is every interface by default; `DRISHTI_CONSOLE_HOST=127.0.0.1`
for this machine only). `drishti-console/requirements.txt` holds FastAPI, Uvicorn, Jinja2, httpx, PyYAML, Markdown, pytest,
`cryptography`, numpy, pandas, pyarrow and deltalake (the last two for `lake build` and `lake demo`, so the console's Python is also the
tools' Python), boto3 for the Bedrock AI provider and, optionally, `brotli`. Without uv, `python3 -m venv drishti-console/.venv` and
`drishti-console/.venv/bin/pip install -r drishti-console/requirements.txt` do the same where the system Python has `ensurepip`.

For Calc (Python on a view, `Alt+C`), install its runtime once: `tools/fetch-pyodide.sh` (about 340 MB downloaded to
`~/.cache/drishti/`, 53 MB kept; see [USER_GUIDE.md](USER_GUIDE.md#12-installing-the-python-runtime)). Without it the console runs
as before, `GET /healthz` says `"pythonRuntime": "not installed: run tools/fetch-pyodide.sh"` and the Calc panel says how to install it.

The console reads `drishti-console/config/application.yaml`, then `drishti-console/config/application.local.yaml` if present
(git-ignored), then environment variables, then `--key=value` arguments:

```bash
# three ways to move the console to port 17481
DRISHTI_CONSOLE_PORT=17481 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
DRISHTI_CONSOLE__SERVER__PORT=17481 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py --server.port=17481
```

`DRISHTI_CONSOLE__A__B` sets any key `a.b` (the prefix is `DRISHTI_CONSOLE__`, double underscores separate the levels). Named
variables such as `DRISHTI_CONSOLE_PORT` and `DRISHTI_BACKEND_URL` exist only where the YAML says `${NAME:default}`. Against a server
with sign-in on, the console needs the same `DRISHTI_TOKEN_SECRET` and `DRISHTI_AUTH_ENABLED=true` (the run configuration **Drishti
console** sets them); without them every call answers `401 DRS-5010` and the console's log says `business date unavailable: DRS-5010
missing bearer token`.

#### 2.5 Running the server from source

Build the jar, then start it from the repository root (the server finds `./config/packs` and `./data` relative to the working
directory):

```bash
./mvnw -q package -DskipTests -pl drishti-server -am
DRISHTI_DEMO_ENABLED=false DRISHTI_STUDIO_SAVE=true \
  java -jar drishti-server/target/drishti-server-2.9.6-exec.jar
```

Server settings follow the same precedence as the console: `drishti-server/src/main/resources/application.yaml`, then
`./application.local.yaml` (git-ignored; imported by `spring.config.import`), then environment variables, then `--key=value`
arguments. For example `--drishti.live.frame=100ms` or `--server.port=18481`. The run configurations in `run-configs/` carry the
settings of each way of starting it (sign-in on with the development admin, the demo source on or off, the QA profile on its own ports
and data folder); [GETTING_STARTED.md (IDE)](GETTING_STARTED.md#running-from-intellij-idea-and-pycharm) lists them.

The development loop that works best:

| You changed | Do |
|---|---|
| A Sutra under `config/packs/*/sutras/` or a site Sutra directory | Nothing: `drishti.rachana.hot-reload` reloads it; an invalid edit keeps the last good version |
| A connector file in `config/connectors/` | Nothing: the folder is watched and only that connector restarts |
| A BI file under `config/bi/` | Nothing: the folder is watched (`drishti.bi.watch`, `BiFolder`); a changed file becomes the next version, one that does not parse keeps its last good version and is listed as a problem |
| Java code | Rebuild the module and the server jar (`./mvnw -q -o -pl drishti-server -am package -DskipTests`), restart the server |
| `pack.yaml` or anything in a pack folder | Nothing, usually: the packs folder is watched (`drishti.packs.watch`, `PackReloader`) and the running packs are reconciled with it; a change that cannot be applied live keeps the last version running, is refused with the reason in the log and on Admin → Packs, and is tried again by itself |
| The demo lakes | `drishti.py lake demo` again: a running server is told (or notices a rebuilt Delta date by itself), so no restart |
| A console template | Reload the page |
| Console Python, or anything under `drishti-console/web/static/` | Restart the console (the asset fingerprint is computed at start-up; see [§8](#8-common-pitfalls)) |

#### 2.6 The drill: `tools/drill.sh`

"Drill it" is how work reaches `main`. The script verifies everything, and only then publishes:

```bash
tools/drill.sh
```

Options: `--docs` (the light drill, below), `--full` (the full drill even when only docs changed), `--no-push` (verify only: no push,
no merge, and it may run on any branch). `DRILL_MVN_ARGS="..."` adds Maven arguments to both Java runs (for example
`-Ddrishti.test.forks=1` to run the test classes serially). The script prints `drill: mode = full` or `drill: mode = docs`, then each
stage's time, then a summary. It is costly (it runs everything): run it once per wave or release, and targeted tests in between.

**Which mode.** The commits since `origin/develop` decide: when every changed file is under `docs/` or `tools/docs/`, is a `*.md` file
or is a `help.yaml`, the drill runs in docs mode by itself; otherwise it runs in full mode. `tools/drill.sh --docs` on anything else is
refused, with the files that are not documentation listed.

**Full mode**, in order (it stops at the first failure, `set -euo pipefail`):

1. Refuses to run unless the current branch is `develop` (`drill: must be on develop`; not checked with `--no-push`) and the working
   tree is clean (`drill: commit your changes first`).
2. `python3 tools/license_headers.py`: every file carries the copyright header.
3. The whole Java build, every test and every rule, offline, on **Java 21** (`JAVA21_HOME`, default
   `/usr/lib/jvm/java-21-openjdk-amd64`), the production runtime, containers included, after `mvn clean` (`./mvnw -q -o clean verify`:
   nothing an earlier build or an interrupted run left in `target/` is built on). It builds the jar the console tests start. Test
   classes run in parallel JVM forks inside each module (`drishti.test.forks`, 4 by default, set in the root `pom.xml`; each fork is
   reused for many classes). Anything a test writes at a fixed path carries `${surefire.forkNumber}` (see `drishti-server/pom.xml`);
   Testcontainers and the in-test servers use free ports. A test must not clear or change a JVM-wide setting for the classes that follow
   it in the same fork.
4. Then three jobs at the same time, each with its own log in `<data dir>/drill/` (`java25.log`, `console.log`, `tools.log`) and its
   own timing; all must pass, and the tail of each failed log is printed:
   - **Java 25**, the forward-compatibility run (`JAVA25_HOME`; skipped with a message when that JDK is absent): the same tests again,
     with Docker hidden so the container tests (already run on 21) are skipped. It runs in a **copy of the committed tree** (a
     `git archive` of `HEAD` into a temporary directory, removed afterwards) with its own `target/` directories, and two test forks.
   - **The console suite**, `pytest -n <min(4, cpus/2)> --dist loadfile drishti-console/tests`: pytest-xdist workers each take whole
     test files, so a browser file keeps its module and session fixtures. Every live server and console in the tests starts on ports
     offset by the worker, in the worker's own scratch directory; the stand-in server object's state is put back after each test
     (`conftest.py`). If it fails, the failed tests (and only those, read from the run's own `FAILED`/`ERROR` summary, since
     `pytest.ini` turns the pytest cache off) run once more, serially, one file at a time and each file under a 20-minute limit (a
     browser that dies mid-test cannot block the drill): a test that passes the second time is printed as `FLAKY` and appended to
     `<data dir>/drill/flakes.log` with the commit; a test that fails twice, a file that hits the limit, or a run that failed without a
     failed test to re-run (a collection error), stops the drill. The Java 25 run re-runs a failed test once too
     (`-Dsurefire.rerunFailingTestsCount=1`): it shares the machine with the console suite, and surefire reports such a test as a flake.
   - **The Python tool tests**: every generator that supports `--check` (generated pack content must match what the generator writes
     now), the `unittest` suites of `tools/` (`samplegen`, `packreg`, `test_sutragen`, `test_ingest_jsonl`, `test_drishti_cli`,
     `test_pack_bundle`, `test_no_hidden_names`, `test_packmake`, `test_pack_authoring`, `test_cli_extras`, `test_pack_deploy`,
     `test_cli_bi`, `test_cli_connectors`, `test_cli_loads`, `test_pack_form`, `test_schemapack`, `tools/testdata/test_testdata.py`),
     and, if `uv` is installed, the lake tests with `deltalake`, `pyarrow` and `pyyaml` supplied by `uv run --with`
     (`samplegen/test_layout.py`, `lake/test_maintain.py`, `lake/test_demo.py`), each under a 10-minute `timeout`. (Tools that start
     worker processes after pyarrow or deltalake have started threads must spawn them, never fork: `bulk_trades.py` uses a `spawn`
     pool.) `tools/lake/test_build.py` is not in the list: run it by hand
     (`cd tools/lake && ../../drishti-console/.venv/bin/python -m unittest test_build`).
5. Pushes `develop`, fast-forwards `main` to `develop` (a fast-forward-only merge), pushes `main`, and returns to `develop`. It prints
   `drilled (full): <the last commit>`.

**Docs mode** runs the licence check, the documentation console tests (`test_docs_*.py`, `test_help*.py`, `test_guide_images.py`,
`test_examples.py`) and the Python tool tests, concurrently, then the same push and merge. No Java build, no browser tests.

The drill removes its own temporary files on exit. The console suite needs `pytest-xdist` (in `requirements-test.txt`). The inotify
watch limit of a loaded desktop (`fs.inotify.max_user_watches`) can make the Sutra hot-reload tests fail in any mode: that is the
machine, not the drill.

#### 2.7 One config, several instances of the same connector

A connector is a *named instance* of a plugin, one file in `config/connectors/<name>.yaml` (the file name is its name;
[CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)). You may run as many instances of a plugin as you like; each has its own
settings, its own `source-name` (shown in provenance), its own entry in Admin → Health and in `GET /api/v1/sources`, and its own cache.

**Several slices of one kind: a union.** The shipped trading pack is the example: three `delta` connectors, `trade-endur`,
`trade-calypso` and `trade-murex`, each reading one source system's table (`table.trade: trade_endur`, …) and each marked
`union: "true"`. A union serves one kind **together**: a read of `trade/MX-20000001` asks all three (ids are unique across them), a
search or a type-ahead merges their hits, labelled by source, and a BI table over `trade` reads the three tables as one
(`source.SourceRouter`, `bi.engine.SourceGateway.plugins`; tests: `UnionConnectorsTest`, `UnionTableTest`). On the demo lakes:

```
$ drishti.py connector test trade-endur --server http://localhost:18480 --token-file ~/drishti-token
trade-endur (delta): reachable, health UP (engine: native), 14 ms
  kind   business date  rows  note
  trade  2026-10-16     5000
  trade  2026-10-15     5000
  trade  2026-10-14     5000
```

and `GET /api/v1/sources` lists `trade-calypso`, `trade-endur`, `trade-murex` (and the derived `trade-pages`) as four sources. Adding a
fourth source system is one more file with `union: "true"` and its own table; no pack changes.

**Several stores of one kind, asked in turn.** Without `union`, instances serving one kind are asked one after another and the first
that holds the entity answers. Two `file` connectors split by age, with the kind's `routes:` entry deciding which is asked first:

```yaml
# config/connectors/recent-files.yaml: the last weeks, written nightly by an export job
plugin: file
kinds: [trade]
settings:
  root: ${FEEDS_RECENT:./data/feeds/recent}
  domain: trading
  lookback-days: 10
  rescan-seconds: 30
  source-name: recent-feed
  stale-after: 2d
```

```yaml
# config/connectors/archive-files.yaml: older months, a different folder
plugin: file
kinds: [trade]
settings:
  root: ${FEEDS_ARCHIVE:./data/feeds/archive}
  domain: trading
  lookback-days: 400
  rescan-seconds: 300
  source-name: archive-feed
```

with `drishti.sources.routes.trade: recent-files` in the site configuration (or a pack's `routes:`). Other plugins follow the same
shape: a Delta connector on S3 (`root: s3a://…`, `s3.endpoint`, `s3.access-key: ${LAKE_ACCESS_KEY}`), a JDBC connector in table mode
(`url`, `user`, `password: ${PG_PASSWORD}`, `table: orders.entities`) or in query mode (`query.<kind>` and `ids.<kind>` SQL), a Kafka
connector in `mode: ticks` (it only pushes changes to open views; the documents come from the dated stores) or `mode: state`.

**How a kind is routed across them.**

* `kinds:` says which kinds an instance serves. Without it, an instance serves what its plugin reports (a `file` or `kafka` instance
  with no `kinds:` serves **every** kind, so give them `kinds:`; a `jdbc` query-mode instance serves the kinds that have a
  `query.<kind>`).
* For a read of `trade/MX-1` Drishti makes a list: the kind's `routes:` entry first (`trade: recent-files`), then `default-route`
  (`demo`, when it runs), then every other running instance that serves `trade`, in a stable order kept across restarts and reloads. For
  a picked date, dated stores (file, Delta, JDBC) go before undated ones; for Live, live ones (Kafka) go first. Union members are not in
  this list one after another: they are asked together, as one.
* The list is asked one by one and the first store that holds the entity answers. **A store that holds the date is authoritative for
  it**: an entity its file for that date does not list is *not held*, and the stores behind it are not asked
  (`DRS-1001 recent-files holds trade for 2026-10-01 and does not list trade/MX-0`). Only a date it does not hold passes on to the next
  store. So keep the dated stores of one kind **disjoint by date**: here `recent-files` holds the last 10 days, `archive-files`
  anything older.
* A store that **fails** (database down, service error) stops the read with `DRS-1003 <name> failed reading …`; it does not fall
  through to another store's data.

What a server answered with the two file connectors above (captured in an earlier release, when the same two connectors were defined
in the server's configuration; the router is the same for files), the files `feeds/recent/trading/2026-10-01/trade.jsonl` (`MX-1`) and
`feeds/archive/trading/2026-06-30/trade.jsonl` (`MX-0`):

```
GET /api/v1/entities/trade/MX-1/raw?asOf=2026-10-01  -> 200, "source":"recent-feed"
GET /api/v1/entities/trade/MX-0/raw?asOf=2026-06-30  -> 200, "source":"archive-feed"   (recent-files does not hold that date)
GET /api/v1/entities/trade/MX-0/raw?asOf=2026-10-01  -> 404 DRS-1001 recent-files holds trade for 2026-10-01 and does not list trade/MX-0
```

**What happens when an external system is not there.** The server **starts** (`/actuator/health` is `UP`); `GET /api/v1/admin/health`
says `DEGRADED` and each connector's health is its own: a local folder or lake reads `UP`, a missing lake folder `DOWN: cannot reach
<root>/<domain> (engine: native)`, a JDBC connector `DOWN: <driver message> (reconnecting)` until the database returns, a Kafka
connector `UP` with no data (its consumer retries), and a connector that cannot even start (a Delta lake on an unreachable S3
endpoint) is listed under `failedToStart` and retried by itself ([CONNECTOR_FILES.md, retries](../connectors/CONNECTOR_FILES.md#a-connector-that-fails-to-start-or-loses-its-connection-is-retried)).
One `DOWN` connector does not take the others with it.

The deprecated form, every connector under `drishti.sources.connectors` in a site file loaded with
`--spring.config.additional-location`, still works (flagged in the log and on Admin → Connectors; it overrides a file setting by
setting); move each block to its own file ([CONNECTOR_FILES.md, section 11](../connectors/CONNECTOR_FILES.md#11-moving-off-applicationyaml-and-the-old-data-source-override)).

---

### 3. Project rules

Some rules are enforced by tests, so a plain `./mvnw verify` fails when they are broken. Others are conventions that reviews hold to.
Both kinds are listed here.

| Rule | Enforced by |
|---|---|
| Every source file carries the copyright header | `LicenseHeaderTest`, `tools/license_headers.py` (drill, CI) |
| No non-UI source file over 1500 lines | `SourceFileSizeTest`; `drishti-console/tests/test_assets_policy.py` for console Python |
| One-way module dependencies, a Spring-free SPI, controllers only in `server`, no field injection, no `Serializable` | `ArchitectureRulesTest` |
| No `synchronized` around blocking I/O (virtual threads) | `NoBlockingUnderSynchronizedTest` |
| Front end vendored: no CDN, no external URL, no inline script, handler or style | `drishti-console/tests/test_assets_policy.py`, the console's Content Security Policy |
| Phone: no sideways scroll, targets of at least 44 px | `test_page_widths_browser.py` and the pages' own browser tests |
| No hidden file or folder of our own; everything written at run time under the data folder | `tools/test_no_hidden_names.py` |
| Generated files match their generators | `--check` in the drill |
| Sutras shown in docs and pack guides are valid | `DocumentedSutrasTest` |
| Every pack Sutra loads without problems | `PackSutrasTest` |
| Warm views stay fast | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99` |
| Every relative link and anchor in the documents resolves; an obsolete design is deleted, not archived: git keeps its history, so move any live content into the current document first, and a document that still mentions it names the release tag where it last lived, as text | `drishti-console/tests/test_docs_rot.py` |
| Object-oriented, concurrent, configuration-driven code; virtual threads; no SQL in code; schema files per database; git author only | Review (this section) |

#### 3.1 The copyright header

Every file whose suffix is `.java`, `.py`, `.js`, `.css`, `.html`, `.yaml`, `.yml`, `.xml`, `.md`, `.properties` or `.sh` must contain
`Copyright (c) 2026 Ashutosh Sinha` within its first 2000 characters. Build output, `.git`, `.venv`, `vendor`, `recorded`,
`__pycache__`, `.mvn`, `.idea` and `docs/requirements` are skipped, as are `mvnw`, `mvnw.cmd` and `LICENSE`. JSON files cannot hold
comments and are exempt. SQL files carry the header in `--` comments by convention.

Check, then insert what is missing:

```bash
python3 tools/license_headers.py          # lists "missing <file>" and exits 1 if any file lacks it
python3 tools/license_headers.py --fix    # inserts the header and prints "fixed <file>"
```

The header text is `config/license-header.txt`, written in the comment style of each suffix: `/* … */` for Java, JavaScript and CSS;
`#` for Python, YAML, properties and shell (after a `#!` line if there is one); `<!-- … -->` for XML and Markdown (after an `<?xml`
line); `{# … #}` for Jinja templates (`.html`). A Markdown document therefore starts like this one.

#### 3.2 File size

`SourceFileSizeTest` fails when any `.java`, `.py`, `.yaml`, `.yml` or `.xml` file outside `drishti-console/web/` has more than 1500
lines (`MAX_LINES = 1500`). UI templates, styles and scripts under `drishti-console/web/` are exempt. When a class approaches the
limit, split it by responsibility (the banking generator, for example, is a dozen focused modules under `tools/packgen/banking/`).

#### Error Prone and Spotless

- **Error Prone** checks every compile. A finding at error level stops the build with the check's name, for example
  `[ReturnValueIgnored] Return value of 'apply' must be used`. Fix the code. If the finding is intended, add
  `@SuppressWarnings("ReturnValueIgnored")` on the smallest element, with a comment saying why. Warnings (missing `@Override`, Javadoc
  summaries) do not fail the build but are worth fixing when you touch the file.
- **Spotless** runs at `verify`. On a failure it prints the diff; `./mvnw -o -q spotless:apply -pl <module>` fixes unused imports,
  trailing whitespace, missing final newlines and tabs. Run it before every commit of Java code.

#### 3.3 Architecture rules

`drishti-it/src/test/java/com/ash/drishti/it/ArchitectureRulesTest.java` checks the compiled main code (tests are excluded) with
ArchUnit:

| Rule | Meaning |
|---|---|
| `apiIsSpringFree` | Nothing in `com.ash.drishti.api..` may use `org.springframework..`. A plugin must not need Spring. |
| `apiDependsOnNothingInternal` | `api` may not use `common`, `rachana`, `engine` or `server`. |
| `lowerLayersDoNotSeeEngine` | `common`, `rachana`, `inference`, `graph` and `identity` may not use `engine` or `server`. |
| `engineDoesNotSeeServer` | `engine` may not use `server`. |
| `controllersOnlyInServer` | `@RestController` classes live only in `com.ash.drishti.server..`. |
| `noFieldInjection` | No field is annotated `@Autowired`. Use constructor injection. |
| `aiLayerDependsOneWay` | Nothing in `com.ash.drishti.ai..` may use `engine`, `server`, `identity`, `rachana` or `packs`: the AI layer sees only the SPI and `common`. |
| `noSerializable` | No class implements `java.io.Serializable`, except enums, throwables and JPA `@Embeddable` keys (JPA requires composite keys to be serialisable). |

Tests may use `@Autowired` fields (`@Autowired MockMvc mvc;`), because test classes are not analysed.

#### 3.4 Object-oriented, concurrent and configuration-driven

- **Beans per module.** Each module contributes its beans from its own `@Configuration` class (`EngineConfiguration`,
  `RachanaConfiguration`, `IdentityConfiguration`, `SecurityConfiguration`, `BiConfiguration`, `MqConfiguration`, …).
  `DrishtiApplication` imports them. Domain classes take their collaborators in the constructor and are plain Java, so they unit-test
  without Spring (ADR-001).
- **Configuration, not constants.** Every limit, timeout, path and switch is a property under `drishti.*`, bound to an immutable
  `@ConfigurationProperties` record whose compact constructor supplies the defaults (`LiveProperties`, `SearchProperties`,
  `GovernanceProperties`, `QueryProperties`). Industry vocabulary belongs in packs, never in core code (ADR-010): no attribute name such
  as `TRADE_ID` appears in Java. The product name and legal notices are configuration too (`drishti.branding.*` on the server, `ui.*` in
  the console): a message names the product with `Branding.sqlName()`, `Branding.netName()` or `Branding.name(fallback)`, a template
  with `{{ PRODUCT }}`, a script with `document.body.dataset.product`; `BrandingLiteralTest` and `tests/test_branding_guard.py` fail on
  a literal.
- **Virtual threads.** `spring.threads.virtual.enabled: true` runs every request on a virtual thread. Blocking I/O (a source read, a
  link fan-out) is fine and cheap. `EngineConfiguration` provides `drishtiVirtualExecutor` (a thread per task) for fetches and SSE
  writers, `drishtiBindPool` (a `ForkJoinPool`, `drishti.engine.bind-parallelism`, 0 = one per core) for CPU-bound panel binding, and
  `drishtiFrameScheduler` (two platform threads) for live frames.
- **Do not hold a monitor across I/O.** Guard I/O with a `ReentrantLock`, not `synchronized`, so a blocked virtual thread releases its
  carrier. The code says so where it matters, for example in `TopicHub` (`a ReentrantLock: the upstream subscribe may do I/O, and
  synchronized would pin`), `FileAuditLog` and the BI store's single-flight builds (`bi.engine.Layers`).
- **Thread safety is the default expectation.** `SourcePlugin` implementations are called concurrently from many virtual threads.
  Shared state is either immutable and swapped whole (`HitIndex` keeps an immutable list in an `AtomicReference`; `PackStateStore` keeps
  an immutable snapshot map that readers use lock-free), or guarded by a lock for writers only. Each class says in its Javadoc whether
  it is thread-safe. Concurrency has its own tests (`HitIndexConcurrencyTest`, `UserServiceConcurrencyTest`, `TopicHubTest`,
  `LiveStreamSlotsTest`).
- **One exception type with a stable code.** Code that crosses a module boundary throws `DrishtiException(ErrorCode, message)`.
  `ApiExceptionHandler` turns it into RFC 7807 `problem+json` with `code: "DRS-nnnn"`. Codes are grouped by first digit (1 sources and
  data, 2 Sutra, 3 inference, 4 engine and graph, 5 API, 6 identity, 7 collaboration, 8 embedding, 9 BI) and are never reused; every code is in
  [API_GUIDE.md](API_GUIDE.md) and a test keeps that table in step with `ErrorCode`.
- **Imperfect data never fails a view.** A panel whose data does not fit is returned empty ("No data available"), never an exception
  (`Binder.bind`, `Emptiness`, `ImperfectDataTest`).
- **The Rachana-EL is closed.** Functions must be pure, total (never throw on odd input) and cheap; adding one is an amendment to
  ADR-003.

#### 3.5 Data access: no SQL in code

The identity database (`drishti-identity`) is reached only through Spring Data JPA:

- Repositories are interfaces nested in `db.IdentityRepositories` and use **derived queries only**
  (`findBySubjectOrActorOrderByIdDesc`), never `@Query` strings or JDBC.
- The schema is two files, `drishti-identity/src/main/resources/db/schema-sqlite.sql` and `db/schema-postgres.sql`. `IdentityDatabase`
  applies the one for the configured URL at every start. Every statement is idempotent (`CREATE TABLE IF NOT EXISTS`), so the file *is*
  the schema: there are **no migrations**.
- Hibernate never changes the schema. On PostgreSQL it **validates** the entities against it (`hibernate.hbm2ddl.auto: validate`), so a
  mismatch stops start-up. SQLite types columns by value, so the check cannot apply there; the identity contract tests round-trip every
  column on both databases instead.

The BI engine's SQL is not hand-written either: `bi.engine.SqlEmitter` and `SqlCompiler` emit parameterised DuckDB SQL from the plan
(values are always parameters; names are checked against the model and quoted by `Dialect.quote`), and `QueryEngineTest` proves that
names and values from a request never change the SQL's shape.

#### 3.6 The front end is vendored

The console uses Bootstrap, Bootstrap Icons, ECharts (and ECharts GL), CodeMirror (with its Python mode), Vega and Vega-Lite (the
`vega-lite` visual and a Python visual's output) and Graphic Walker (Calc's `drishti_bi.walk`, in a sandboxed frame), copied under
`drishti-console/web/static/vendor/`; `vendor/VENDOR.md` lists each library with its version, licence, size and whether the repository
holds it. FINOS Perspective, DuckDB-Wasm and Apache Arrow JS were removed in 2.3.0 (wave B): **the servers compute every number and the
browser draws**, so no BI page loads WebAssembly (a 2.1 report's `perspective-*` visuals are drawn as Drishti's own grid of the server's
answer). Pyodide, for Calc only, is installed by script, not committed (below). There is no npm, no build step and no CDN (ADR-006).
The Content Security Policy in `drishti-console/core/app.py` is:

```text
default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; font-src 'self';
connect-src 'self'; frame-src 'self'; worker-src 'self'; frame-ancestors 'self'
```

Two files have their own, narrower policy. Calc's worker, `/static/js/calc-worker.js`, is served with
`default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src <host>/static/ <host>/pyodide/`, so it alone may compile
WebAssembly (Pyodide) and it can fetch nothing but static files and the runtime (never an `/api` route, which would carry the user's
session). Calc's Graphic Walker page, `/static/calc/walker.html`, needs `unsafe-eval` and inline styles for the library, so it is a
sandboxed frame (`sandbox allow-scripts`, no same-origin) with `connect-src 'none'`: the rows it is given by message cannot leave it.
For Pyodide, `tools/fetch-pyodide.sh` downloads a pinned release, checks its SHA-256 and unpacks what Calc needs into
`drishti-console/web/static/vendor/pyodide/` (git-ignored); the console serves it at `/pyodide/<version>/`
([USER_GUIDE.md](USER_GUIDE.md#12-installing-the-python-runtime)).

So templates may not contain inline `<script>` blocks, `on…=` handlers or `style=` attributes, and nothing may load from another origin.
`drishti-console/tests/test_assets_policy.py` checks all of this. Behaviour goes in a file under `web/static/js/`, wired by `data-*`
attributes; widths and colours come from classes and `data-*` values that a script applies (bars and gauges use `data-w`, see
`view.js`).

#### 3.7 Git

- Work happens on `develop`; `main` only moves by fast-forward through the drill.
- Commits are authored as the repository owner (`ajsinha`). Do not add co-author or tool-attribution trailers.
- Commit messages say what changed for a user or an operator, in plain sentences (see `git log`).

---

### 4. How a request travels through the code

This section follows `TRD MX-20000001 <GO>` from the keyboard to the screen, then the live updates that follow, then a BI question
and its live push.

#### 4.1 From the command line to a view

| Step | Where | What happens |
|---|---|---|
| 1 | `drishti-console/web/templates/terminal/_topbar.html`, `web/static/js/command.js` | The command line is a plain form (`action="/go"`). `command.js` adds the dropdown: it calls the console's `GET /api/suggest`, which calls the server's `GET /api/v1/command/suggest` (`CommandController.suggest` → `SuggestionService`), filtered by `Entitlements.filter`. With a union of trade connectors, the hits of all three are merged. |
| 2 | `drishti-console/routes/terminal_routes.py` `go()` | Text that looks like a search (`<MN> where …`, `order by`, `limit`) is redirected to `/s`. Otherwise `BackendClient.command()` posts `{"text": "TRD MX-20000001"}` to `POST /api/v1/command`. |
| 3 | `drishti-console/core/backend.py` `BackendClient._send` | Every call carries the caller (`X-Drishti-User`, and `Authorization: Bearer …` when sign-in is on) and the business date (`X-Drishti-As-Of`, from `core/asof.py`, which `AuthGate` in `core/app.py` sets from the `drishti_asof` cookie). An unreachable server becomes `BackendError(503, "DRS-5003", …)`. |
| 4 | `server.security.PathGuard`, `server.security.TokenFilter` | `PathGuard` first refuses a request line under `/api` or `/actuator` that does not spell its path plainly (`;`, a needless `%`-escape, a dot or empty segment: `400 DRS-5001`); filters decide on `RequestPaths.routed`, the decoded path that is served, never the raw URI. For every `/api/v1/**` request: with security off, the caller is `Principal.anonymous(X-Drishti-User)` with every role; with it on, the bearer token is verified (`TokenVerifier`: a session token signed with `DRISHTI_TOKEN_SECRET`, or a personal API token `drk_…`) or the answer is `401 DRS-5010`. The principal is a request attribute (`Principal.ATTRIBUTE`). |
| 5 | `server.api.AsOfResolver` | Gives any controller parameter of type `AsOf` the request's business date: `X-Drishti-As-Of`, else `?asOf=`, else the current business date (`BusinessDates.parse`). |
| 6 | `server.api.CommandController.command` | `CommandParser.parse` turns the text into an `EntityRef` (`trade/MX-20000001`). `Entitlements.requireOpen` checks the caller's roles and active packs (`403 DRS-5002`). If the entity exists (`SourceRouter.fetchAll`), the answer is its ref (`{"ref":{"kind":"trade","id":"MX-20000001"},"mnemonic":"TRD",…}`); otherwise the text becomes a pick list (`SearchQuery.pick` + `StructuredSearch.run`), and exactly one match opens directly. An unreadable command is `400 DRS-4001`; a search that names a field the kind does not have is `400 DRS-4004` with suggestions (`no trade has a field 'NOTIONAL'; did you mean LEGS.CASH_FLOWS.NOTIONAL, LEGS.NOTIONAL?`). |
| 7 | `terminal_routes.go()` | Redirects (303) to `/v/trade/MX-20000001`, or to `/s?q=…` for a pick list. |
| 8 | `terminal_routes.view()` | `BackendClient.view()` calls `GET /api/v1/views/trade/MX-20000001`. |
| 9 | `server.api.ViewController.view` | `requireOpen` again, then `ViewPipeline.view(ref, asOf)` (timed as the `drishti.view` metric), then `Entitlements.restrict` (disables links the caller may not follow), then `RecentEntities.touch` (feeds "recent" in the dropdown). |
| 10 | `engine.ViewPipeline` | The pipeline (below) returns a `ViewModel`. |
| 11 | `terminal_routes.view()` | Splits the panels into `main` and `right` by `area`, renders `terminal/view.html`. Each panel is drawn by the `panel(p)` macro in `web/templates/_macros/panels.html`; `web/static/js/view.js` adds charts (vendored ECharts), tabs, F-keys and the raw-JSON drawer. |

#### 4.2 Inside `ViewPipeline`

`ViewPipeline` (`drishti-engine`) documents itself as "command → fetch → classify → fingerprint → layout → link → bind". In code:

1. **Fetch.** `SourceRouter.fetch(ref, asOf)` picks the plugins that serve the kind (the kind's route, the union it belongs to, named
   connectors, `default-route`), tries dated sources first for a picked date, and gives up after `drishti.sources.fetch-timeout` (5s).
   No source holding it is `404 DRS-1001`.
2. **Match a Sutra.** `SutraMatcher.match(kind, data)` asks `SutraRegistry` for the highest-priority Sutra of the kind whose
   `match.where` expression holds for this document (for a trade: `SOURCE_SYSTEM` and `PRODUCT_TYPE`; `MX-20000001` matches the MUREX
   `irs-fixfloat` Sutra). None means inference alone.
3. **Fingerprint the shape.** `ShapeFingerprinter` hashes the document's structure (not its values). It is cached per (entity,
   generation, business date).
4. **Lay out.** `LayoutMerger.merge(sutra, data, kind)` merges the Sutra with inferred panels into an `EffectiveLayout`. Layouts are
   cached in Caffeine per (Sutra *object identity*, kind, fingerprint), so the cost of inference is paid once per shape, and a reloaded
   Sutra can never be served an older layout. A registry change clears the cache.
5. **Link.** `ReferenceCatalog.discover` finds fields that reference other entities (from the packs' `graph.fields` and
   `graph.id-patterns`: `COUNTERPARTY` → `counterparty`, `PORTFOLIO` → `book`, …), and line charts may name a `source` entity. All are
   fetched in parallel by `SourceRouter.fetchAll` within `drishti.graph.link-budget` (40ms); late ones show as pending.
6. **Bind.** Each panel is bound by `Binder.bind(panel, BindContext)` in parallel on `drishtiBindPool`. Expressions are compiled once by
   `ElCompiler` (Rachana-EL) and values formatted by `Formats` and toned by `Tones`. The strip, title and F-keys are bound the same way.
7. **Return** a `ViewModel(ref, mnemonic, title, strip, panels, keys, provenance, timings)`. `provenance` carries the layout label, the
   fingerprint, source, generation, `live` and business date; `timings` has `fetch`, `layout`, `links`, `bind` and `total` in
   milliseconds. On the demo lakes, `GET /api/v1/views/trade/MX-20000001` with `X-Drishti-As-Of: 2026-10-15` answered
   `"layout": "Sutra irs-fixfloat v1 + inference"` and `timings` `{"fetch": 2.95, "layout": 0.57, "links": 5.57, "bind": 3.01,
   "total": 12.09}`.

#### 4.3 Live updates of a view

| Step | Where | What happens |
|---|---|---|
| 1 | `terminal/view.html` | `live.js` is loaded only when `vm.provenance.live` is true. A picked business date is a static snapshot: no stream. A Delta lake is not live (its documents change by day); live entities come from a stream (Kafka, ActiveMQ, RabbitMQ, Redis) or the demo source. |
| 2 | `web/static/js/live.js`, `web/static/js/channel.js`, `web/static/js/live-hub.js` | `live.js` subscribes `view:trade/MX-20000001` on `window.DrishtiChannel`. `channel.js` hands every tab's subscriptions to one hub per browser (`live-hub.js`, in a SharedWorker, or in a tab elected with a Web Lock that relays over a BroadcastChannel), which keeps **one** `EventSource` for the whole browser, to the console's `GET /api/channel?s=…`, and adds or removes subscriptions with `POST /api/channel/{cid}` instead of reconnecting. Workspace panes (iframes) share their parent's subscriptions. Tests: `tests/test_live_hub.py` (the protocol, in Node), `tests/test_live_tabs_browser.py` (Chromium). |
| 3 | `drishti-console/routes/api_routes.py` `channel()` | For each subscription it opens an upstream stream (`BackendClient.stream` → `GET /api/v1/views/{kind}/{id}/stream`; `alerts`, `monitor:<name>` and `mq:<id>` go to their own server streams), in **detached** tasks, and multiplexes everything into one SSE response as `{"ch": "<subscription>", "d": …}`. Frames are rewritten by `_view_event`: each patched panel is rendered to HTML with the same `panels.html` macro as first paint (charts stay data). A watchdog ends a channel nobody has read for five seconds; a comment every ~15s keeps proxies from closing it. |
| 4 | `server.api.StreamController.stream` | Takes a slot from `LiveStreamSlots` (cap `drishti.live.max-streams`), builds the initial view, and creates a `ViewStream`. A writer on a virtual thread sends a `view` event, then a `frame` event per frame, or a heartbeat comment (`drishti.live.heartbeat`, 15s). Each client has its own latest-wins `FrameMailbox`, so a slow client never slows others. |
| 5 | `engine.live.TopicHub` | One topic per live entity, shared by every view of it, holding a single source subscription (`SourceRouter.subscribe` → the plugin's `subscribe`). Ticks land in a latest-wins slot and are delivered at most once per frame (`drishti.live.frame`, 50ms). The subscription closes with the last listener. |
| 6 | `engine.live.ViewStream`, `PatchDiffer` | On a tick, the view is rebuilt (`ViewPipeline.build`) and `PatchDiffer.diff(before, after)` produces `Patch`es; they travel as a `Frame`. A deletion (`EntityDocument.deleted()`) is sent as one `deleted` patch instead, and nothing is rebuilt until the entity comes back (`restored`). |
| 7 | `live.js` | Applies the patches in place: strip cells get new text and tone, panels are swapped for the server-rendered HTML, charts move to new data, and changed values flash. A `deleted` patch greys the view under a "deleted at" banner. |

[DATA_ARCHITECTURE.md](../architecture/DATA_ARCHITECTURE.md#1-live-updates) has the details: frames, coalescing, reconnects and the latency metrics.

#### 4.4 A BI question: model query, DEL and SQL

Rūpaka, the BI engine, lives in `drishti-server` under `server.bi` and follows the model architecture of
[BI_DESIGN.md section 14](../architecture/BI_DESIGN.md#14-the-model-architecture-decisions-d1-to-d6): **datasets** (one governed table each, every pack kind publishing
one automatically, `bi.layer.PackDatasets`), **data models** (datasets in a star, relationships, measures written in **DEL**, the
Drishti Expression Language, [BI_LANGUAGE_REFERENCE.md](BI_LANGUAGE_REFERENCE.md#1-expressions-the-drishti-expression-language-del)), and **consumers** (reports, boards, notebooks, alerts, subscriptions,
exports, embeds, AI) each bound to one model. Every consumer asks a model the same way: a **model query** (MQ), a versioned JSON or YAML
question ([API_GUIDE.md](API_GUIDE.md), BI_DESIGN.md › The model layer: the specification of wave W1, section 5). Drishti SQL ([BI_LANGUAGE_REFERENCE.md](BI_LANGUAGE_REFERENCE.md#2-drishti-sql-read-only-sql-over-models)) is a second front end onto the same
plan. Nothing is computed in the browser.

| Step | Where | What happens |
|---|---|---|
| 1 | `web/static/js/bi-consume.js` (a report, a board, Analyse) | A visual becomes one model query; the browser posts it to the console's `/bi/api/mq` (or `/bi/api/models/{id}/query`), which passes it to the server as the signed-in user. |
| 2 | `server.bi.mq.MqController` (`POST /api/v1/bi/mq`), `bi.sql.SqlController` (`POST /api/v1/bi/sql`) | Read on the model is checked (`BiAccess`), then `MqParser` (or `DsqlParser` for SQL) reads the question. |
| 3 | `bi.mq.MqService`, `MqValidator`, `MqCompiler` | Validates the question against the model (names, types, additivity, the currency guard, `DRS-91xx` problems with the field to fix), and compiles it to the engine's `QueryRequest`s: relative windows from the as-of date and the calendar, `top` as its own grouped request, totals per level for measures that do not add up. |
| 4 | `bi.plan.Planner`, `SecurityRewriter`, `Guards`, `Optimiser`, `TierPlanner` | The logical plan: each measure aggregated at its own grain, facts met on shared fields, the reader's row rules and masks applied **before** any aggregation or join (stage 4), guards (a join that would repeat rows is refused), then each node placed on a tier: the source (a SQL store that can aggregate), the console backend (Python nodes) or the Java tier. |
| 5 | `bi.plan.ResultCache`, `bi.engine.QueryService` | The answer cache, keyed by plan hash, security signature and the model data's **generation** (any load, refresh or live batch moves it, so a stale answer is never served). Then admission (server and per-user concurrency) and the smallest layer that can answer. |
| 6 | `bi.engine.Layers` | The BI store (`<data>/bi-store`): typed Parquet **columns** per table and business date, read once from the connectors (`ServerSourceGateway` → the union of trade connectors, a lake, a database) and **rollups** built from them; single-flight per partition. A data-load signal (`POST /api/v1/packs/{pack}/loads`, sent by `lake build --signal` and `lake demo`) clears the date's partitions; each partition also records the source data version it was read from (a Delta date's files), so a rebuilt lake is read again even without a signal. |
| 7 | `bi.engine.DuckDbEngine`, `SqlEmitter` | One in-process DuckDB runs the parameterised SQL over the layer's Parquet files, each query with a watchdog at the time limit; row and byte limits apply. |
| 8 | `MqService` | Writes the one answer shape: typed columns with stable ids, additivity and masks, the rows, the totals, and the meta (as-of, dates, layer, tables, masks, warnings, cache, time, the data generation). The console draws it with ECharts or Vega-Lite. |

`EXPLAIN` (an MQ with `explain: true`, or `EXPLAIN SELECT …` in SQL) answers the plan with the tier and time of each node.

#### 4.5 Live BI: pushed, not polled

| Step | Where | What happens |
|---|---|---|
| 1 | `bi-consume.js` `follow()` | A live visual posts its model query to `/bi/api/mq/subscribe` (`POST /api/v1/bi/mq/subscribe`, `{query, throttle, deliver}`) and gets its first answer and a subscription id, then subscribes `mq:<id>` on the browser's one channel (section 4.3). A closed tab ends its standing queries (`DELETE …/mq/subscribe/{id}`, sent with `keepalive`); an unread one is dropped after `drishti.bi.mq.idle-close` (60 s). |
| 2 | `bi.engine.LiveTables` | For a dataset whose time mode is `live`, one in-memory table per live table, started on first use: it reads the kind once and subscribes to its entities through the connectors that push (a stream, the demo source); nothing in it is per reader. |
| 3 | `bi.engine.GenerationBus` | Every change of the data a model reads is published once, in order: a live batch with its change set, a data load or refresh for a business date (`backdated` when the date is past), a history sample. |
| 4 | `bi.mq.StandingQueries`, `StandingQueryRegistry`, `Maintainer` | Only the subscriptions on the model whose data moved are evaluated, grouped by (plan hash, security signature): one evaluation per group however many viewers share it, each delivered to its own throttled, latest-wins mailbox. A group is kept current by difference (sums and counts), by re-asking the touched groups, or on a tick when past the model's maintenance budget. |
| 5 | `MqController` `GET /api/v1/bi/mq/stream` → the console's channel | Server-sent `answer` events (at most one per throttle; the latest state wins) and `status` events. A visual's chip says **Live** while changes flow, **Listening** while it follows data that has not changed (a daily lake), and why a subscription was refused. |

---

### 5. Recipes

Each recipe lists every file you touch. New files need the copyright header (`python3 tools/license_headers.py --fix` inserts it); the
snippets below leave it out to save space.

#### 5.1 Add a REST endpoint

**Goal:** `GET /api/v1/mnemonics` lists the mnemonics the caller may use; `GET /api/v1/mnemonics/{code}` gives one, or `404 DRS-4005`.
(There is no such endpoint today: on the demo stack `GET /api/v1/mnemonics` answers `404`.)

**1. Add the error code** to `drishti-common/src/main/java/com/ash/drishti/common/ErrorCode.java`, in its group (4 = engine and graph),
with the next free number. Never reuse a code.

```java
    BAD_SEARCH("DRS-4004", 400),
    MNEMONIC_NOT_FOUND("DRS-4005", 404),
```

**2. Write the controller** in `drishti-server/src/main/java/com/ash/drishti/server/api/MnemonicController.java`. Controllers live in
`server.api` (an ArchUnit rule), take their collaborators in the constructor, read the caller from the request attribute, and check
entitlements before doing anything:

```java
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.command.CommandsProperties;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The mnemonics the caller may type: code, kind and label, within the kinds the caller may open. */
@RestController
@RequestMapping("/api/v1/mnemonics")
public class MnemonicController {

    /** One mnemonic. */
    public record MnemonicInfo(String code, String kind, String label) {}

    private final Mnemonics mnemonics;
    private final Entitlements entitlements;

    public MnemonicController(Mnemonics mnemonics, Entitlements entitlements) {
        this.mnemonics = mnemonics;
        this.entitlements = entitlements;
    }

    @GetMapping
    public List<MnemonicInfo> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return mnemonics.all().entrySet().stream()
                .filter(e -> entitlements.mayOpen(p, e.getValue().kind()))
                .map(e -> new MnemonicInfo(e.getKey(), e.getValue().kind(), e.getValue().label()))
                .toList();
    }

    @GetMapping("/{code}")
    public MnemonicInfo one(@PathVariable String code, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        CommandsProperties.Mnemonic m = mnemonics.of(code)
                .orElseThrow(() -> new DrishtiException(ErrorCode.MNEMONIC_NOT_FOUND, "no mnemonic '" + code + "'"));
        entitlements.requireOpen(p, m.kind());
        return new MnemonicInfo(code.toUpperCase(Locale.ROOT), m.kind(), m.label());
    }
}
```

Points to copy from the existing controllers:

- `@RequestAttribute(Principal.ATTRIBUTE) Principal p` is how every controller learns the caller.
- `entitlements.requireOpen(p, kind)` (roles and active packs), `requireAdmin(p)` for `/api/v1/admin/**`, and
  `mayOpen`/`filter`/`redact`/`restrict` to trim answers. Field masks have one source, `entitlements.redactor(p)`: pass it to every
  engine call that reads documents for the caller (`pipeline.view(ref, asOf, redactor)`, `records`, `ViewStream`, `impact.analyse`,
  `search.run`), never mask an answer after the fact. They throw `DrishtiException(FORBIDDEN, …)`, which becomes `403 DRS-5002`.
- Add a parameter of type `AsOf` when the answer depends on the business date; `AsOfResolver` fills it.
- Throw `DrishtiException`; never build error responses by hand. `ApiExceptionHandler` writes the `problem+json` with the code, and
  logs 5xx errors.
- Admin actions that change something are audited: `users.recordAudit(p.user(), "cache-purged", name, detail)` in `CacheController` is
  the pattern.
- A personal API token reads only, unless it was made with a scope that opens this `METHOD path`: a write endpoint adds its pattern to
  `drishti.security.token-scopes` in `application.yaml` (`TokenScopeGuardTest` fails when a write endpoint is in no scope).

**3. Test it with MockMvc** in `drishti-server/src/test/java/com/ash/drishti/server/MnemonicApiTest.java`. With security off (the
default), pass the user in `X-Drishti-User`. To test refusals, turn security on and mint a token with
`TokenVerifier.mint(user, roles, ttlSeconds)`, as `CacheAdminTest` does. Name the shipped packs the test needs: here
`counterparty-risk` (which brings `core`, `market-data` and `trading`), whose `credit-risk` role opens every kind and whose
`trader` role (from `trading`) cannot open netting sets:

```java
package com.ash.drishti.server;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Mnemonics are listed within what the caller may open; an unknown one is DRS-4005. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class MnemonicApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void listsResolvesAndRefuses() throws Exception {
        String risk = "Bearer " + tokens.mint("rita", List.of("credit-risk"), 300);
        mvc.perform(get("/api/v1/mnemonics").header("Authorization", risk)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='NSET')].kind").value(hasItem("netting-set")));
        mvc.perform(get("/api/v1/mnemonics/nset").header("Authorization", risk))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("NSET"));
        mvc.perform(get("/api/v1/mnemonics/NOPE").header("Authorization", risk))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4005"));

        String trader = "Bearer " + tokens.mint("tom", List.of("trader"), 300);   // the trading pack's trader role cannot open netting sets
        mvc.perform(get("/api/v1/mnemonics/NSET").header("Authorization", trader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
        mvc.perform(get("/api/v1/mnemonics")).andExpect(status().isUnauthorized());   // no token
    }
}
```

**4. Document it.** Add the endpoint to [API_GUIDE.md](API_GUIDE.md) and the new code to its error-code table, and to the code table in
[TROUBLESHOOTING.md](TROUBLESHOOTING.md) (the console tests `test_docs_api_endpoints.py` and `test_docs_error_codes.py` fail when one is
missing). The OpenAPI document at `/api/docs` picks the endpoint up by itself.

**5. Call it from the console** (if a page needs it): add a method to `BackendClient` in `drishti-console/core/backend.py`, following
its pattern (`return await self._get("/mnemonics", ident)`), and a matching method to `FakeBackend` in
`drishti-console/tests/conftest.py`.

#### 5.2 Add a source plugin

See [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md): the SPI, testing with the testkit contracts and a
worked, contract-tested plugin end to end. Declare its settings (`settingSpecs()`, or `drishti/plugin-settings.yaml` for a shipped
plugin) so Admin → Connectors builds its form; if the BI store should notice when its data changes without a data-load signal,
implement `SourcePlugin.dataVersion(kind, date)` (the Delta plugin answers each date's file set). Then add the module to the plugin table
in §1.2 and in `README.md`.

#### 5.3 Add a Rachana-EL function

**Goal:** `round(x, digits?)` rounds half up: `round(2.345, 2)` is `2.35`.

Adding a function changes the grammar: record it as an amendment to
[ADR-003](../architecture/adr/003-sutra-is-yaml-with-a-closed-expression-language.md). Functions must stay pure, total (odd input gives
`null`, never an exception) and cheap. (Rachana-EL lays out views; BI measures are written in DEL, which has its own catalogue,
`bi.expr.FunctionCatalog`, and its own guide, [BI_LANGUAGE_REFERENCE.md](BI_LANGUAGE_REFERENCE.md#1-expressions-the-drishti-expression-language-del).)

**1. Register it** in `drishti-rachana/src/main/java/com/ash/drishti/rachana/el/Functions.java`. `ARITY` holds `{min, max}` arguments
(the parser checks it and says `unknown function '…'; known: …` for a name not in it), and `FUNCTIONS` holds the implementation:

```java
    static final Map<String, int[]> ARITY = Map.ofEntries(
            // … the existing entries …
            Map.entry("contains", new int[] {2, 2}), Map.entry("startsWith", new int[] {2, 2}),
            Map.entry("round", new int[] {1, 2}));

    private static final Map<String, ElFunction> FUNCTIONS = Map.ofEntries(
            // … the existing entries …
            Map.entry("round", Functions::round));

    /** Half-up rounding to 0-12 decimals; null for anything that is not a finite number. */
    private static Object round(java.util.List<Object> a, EvalContext c) {
        double v = Values.number(a.get(0));
        if (!Double.isFinite(v)) {
            return null;
        }
        int digits = a.size() > 1 ? (int) Math.max(0, Math.min(12, Values.number(a.get(1)))) : 0;
        return Values.normalise(java.math.BigDecimal.valueOf(v).setScale(digits, java.math.RoundingMode.HALF_UP).doubleValue());
    }
```

`Values.number` turns any value (a number, numeric text, a boolean) into a `double` (`NaN` when it cannot), and `Values.normalise` makes
whole results `Long`, so `round(7.5)` prints `8`.

**2. Test it** in `drishti-rachana/src/test/java/com/ash/drishti/rachana/el/ElTest.java`, with its `eval` helper over the test document
(`notional` is 50,000,000; the test document is the unit tests' own, so its names need not follow a pack's):

```java
    @Test
    void roundsHalfUpAndIsTotal() {
        assertThat(eval("round(2.345, 2)")).isEqualTo(2.35);
        assertThat(eval("round(7.5)")).isEqualTo(8L);
        assertThat(eval("round($.notional / 1000000)")).isEqualTo(50L);
        assertThat(eval("round($.nope, 2)")).isNull();
        assertThat(eval("round('abc')")).isNull();
    }
```

**3. Document it** in the functions row of [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md) and in
[SUTRA_DEVELOPER_GUIDE.md](SUTRA_DEVELOPER_GUIDE.md).

#### 5.4 Add a panel kind

See [RACHANA_REFERENCE.md (developing a panel kind)](RACHANA_REFERENCE.md#developing-a-panel-kind): the complete, tested walk-through (the `metric` tile end
to end: grammar, binder, console, workbench, export, accessibility and the guard tests).

#### 5.5 Add a pack

See this document: the complete walk-through (a help-desk pack from an empty folder), every
`pack.yaml` key, generators, signing and the tests that cover packs. Users and admins: [Part V. Domain packs: the packs that ship, turning them on, who sees which](#part-v-domain-packs-the-packs-that-ship-turning-them-on-who-sees-which). Give the pack's documents
UPPER_SNAKE attribute names, its samples as `samples/<kind>.jsonl`, its connectors as templates (the server writes the files), and its
Sutras a folder layout that suits it (folders are organisation only).

#### 5.6 Add a table to the identity database

**Goal:** a `drishti_notice` table: site-wide notices that administrators switch on and off.

**1. The entity**, `drishti-identity/src/main/java/com/ash/drishti/identity/db/NoticeEntity.java`, in the style of `PackStateEntity`
(public fields, explicit column names):

```java
package com.ash.drishti.identity.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A site-wide notice ({@code drishti_notice}), shown while active. */
@Entity
@Table(name = "drishti_notice")
public class NoticeEntity {

    @Id
    @Column(name = "name", length = 64)
    public String name;

    @Column(name = "text", nullable = false)
    public String text = "";

    @Column(name = "active", nullable = false)
    public boolean active;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Column(name = "updated_by", nullable = false)
    public String updatedBy = "";
}
```

A composite key is an `@Embeddable` class (the one place `Serializable` is allowed); see `PreferenceEntity.Key`.

**2. The repository**, nested in `db/IdentityRepositories.java`. Derived queries only:

```java
    public interface Notices extends JpaRepository<NoticeEntity, String> {
        List<NoticeEntity> findByActiveTrueOrderByName();
    }
```

**3. Both schema files.** Add the table to **both** `db/schema-sqlite.sql` and `db/schema-postgres.sql`, idempotent, with matching
names. Follow the existing type mapping: SQLite `TEXT`, `INTEGER` for booleans, `TIMESTAMP`; PostgreSQL `VARCHAR(n)`/`TEXT`, `BOOLEAN`,
`TIMESTAMP WITH TIME ZONE`.

```sql
-- schema-sqlite.sql
CREATE TABLE IF NOT EXISTS drishti_notice (
    name       TEXT    PRIMARY KEY,
    text       TEXT    NOT NULL DEFAULT '',
    active     INTEGER NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    updated_by TEXT    NOT NULL DEFAULT ''
);
```

```sql
-- schema-postgres.sql
CREATE TABLE IF NOT EXISTS drishti_notice (
    name       VARCHAR(64)  PRIMARY KEY,
    text       TEXT         NOT NULL DEFAULT '',
    active     BOOLEAN      NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(64)  NOT NULL DEFAULT ''
);
```

There are no migrations. A **new table** is created on the next start of an existing installation. A **new column on an existing
table** is not added by `CREATE TABLE IF NOT EXISTS`; it needs its own idempotent statement in each file (and a release note), so prefer
new tables or nullable columns, and say in the release notes what an upgrade does. Hibernate validates the PostgreSQL schema at
start-up, so a column the entity has and the file lacks stops the server.

**4. The store**, `drishti-identity/src/main/java/com/ash/drishti/identity/NoticeStore.java`, in the style of `PackStateStore`: an
immutable snapshot for readers, a lock for writers, every change in a transaction and audited:

```java
package com.ash.drishti.identity;

import com.ash.drishti.identity.db.IdentityRepositories;
import com.ash.drishti.identity.db.NoticeEntity;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.support.TransactionTemplate;

/** Site-wide notices: readers see an immutable snapshot (lock-free); changes are serialised and audited. */
public final class NoticeStore {

    public record Notice(String name, String text, Instant updatedAt, String updatedBy) {}

    private final IdentityRepositories.Notices notices;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ReentrantLock writes = new ReentrantLock();
    private volatile List<Notice> active;

    public NoticeStore(IdentityRepositories.Notices notices, TransactionTemplate tx, AuditLog audit) {
        this.notices = notices;
        this.tx = tx;
        this.audit = audit;
        reload();
    }

    private void reload() {
        active = tx.execute(s -> notices.findByActiveTrueOrderByName().stream()
                .map(e -> new Notice(e.name, e.text, e.updatedAt, e.updatedBy)).toList());
    }

    /** The active notices; lock-free. */
    public List<Notice> active() {
        return active;
    }

    public void set(String name, String text, boolean on, String actor) {
        writes.lock();
        try {
            tx.executeWithoutResult(s -> {
                NoticeEntity e = notices.findById(name).orElseGet(NoticeEntity::new);
                e.name = name;
                e.text = text;
                e.active = on;
                e.updatedAt = Instant.now();
                e.updatedBy = actor;
                notices.save(e);
            });
            reload();
            audit.record(actor, "notice-set", name, on ? "on" : "off");
        } finally {
            writes.unlock();
        }
    }
}
```

**5. The bean**, in `IdentityConfiguration`:

```java
    @Bean
    public NoticeStore noticeStore(IdentityRepositories.Notices notices, TransactionTemplate identityTransactions, JpaAuditLog auditLog) {
        return new NoticeStore(notices, identityTransactions, auditLog);
    }
```

**6. The contract test.** Add a test to `drishti-identity/src/test/java/com/ash/drishti/identity/IdentityStoreContract.java`. It runs
twice: `SqliteIdentityStoreTest` (always) and `PostgresIdentityStoreTest` (PostgreSQL 18 in Docker; skipped without Docker). The
contract's `schemaIsIdempotentSoAStartAgainstAnExistingDatabaseChangesNothing` already checks that a second start against the same
database works.

```java
    @Test
    void noticesRoundTripOnEveryDatabase() {
        NoticeStore notices = bean(NoticeStore.class);
        notices.set("maintenance", "Read-only from 18:00 NY", true, "drishti-dev-admin");
        assertThat(notices.active()).extracting(NoticeStore.Notice::text).containsExactly("Read-only from 18:00 NY");
        notices.set("maintenance", "", false, "drishti-dev-admin");
        assertThat(notices.active()).isEmpty();
        assertThat(bean(AuditLog.class).recent(10, "maintenance")).extracting(AuditLog.Event::action).contains("notice-set");
    }
```

Run it on both databases before you merge (Docker must be running for the PostgreSQL half):

```bash
./mvnw -q -o -pl drishti-identity -am test -Dtest='SqliteIdentityStoreTest,PostgresIdentityStoreTest' -Dsurefire.failIfNoSpecifiedTests=false
```

#### 5.7 Add a configuration property

**Goal:** `drishti.notices.max-length` (default 500), settable as `DRISHTI_NOTICE_MAX_LENGTH`, used by `NoticeStore` from the previous
recipe (apply that one first).

**1. A record** with its defaults in the compact constructor and each key documented as a `@param`, in
`drishti-identity/src/main/java/com/ash/drishti/identity/NoticeProperties.java`:

```java
package com.ash.drishti.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.notices.*}.
 *
 * @param maxLength the longest notice text an administrator may set
 */
@ConfigurationProperties("drishti.notices")
public record NoticeProperties(Integer maxLength) {

    public NoticeProperties {
        maxLength = maxLength == null || maxLength <= 0 ? 500 : maxLength;
    }
}
```

Boxed types (`Integer`, `Boolean`, `Duration`) let the constructor tell "not set" from a value. If a record has a second constructor,
mark the canonical one `@ConstructorBinding`, as `SearchProperties` does.

**2. Register it** on the module's configuration class: in `IdentityConfiguration`,
`@EnableConfigurationProperties(IdentityProperties.class)` becomes

```java
@EnableConfigurationProperties({IdentityProperties.class, NoticeProperties.class})
```

and inject it where it is used. The `noticeStore` bean takes it as one more parameter:

```java
    @Bean
    public NoticeStore noticeStore(IdentityRepositories.Notices notices, TransactionTemplate identityTransactions, JpaAuditLog auditLog,
            NoticeProperties noticeProperties) {
        return new NoticeStore(notices, identityTransactions, auditLog, noticeProperties.maxLength());
    }
```

and `NoticeStore` keeps the limit and checks it first in `set` (add the imports `com.ash.drishti.common.DrishtiException` and
`com.ash.drishti.common.ErrorCode`):

```java
    private final int maxLength;

    public NoticeStore(IdentityRepositories.Notices notices, TransactionTemplate tx, AuditLog audit, int maxLength) {
        this.notices = notices;
        this.tx = tx;
        this.audit = audit;
        this.maxLength = maxLength;
        reload();
    }

    public void set(String name, String text, boolean on, String actor) {
        if (text.length() > maxLength) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a notice is at most " + maxLength + " characters");
        }
        writes.lock();
        // … as before …
```

To add a key to an **existing** record instead, add a component to it. Every `new XxxProperties(...)` call in tests then needs the
extra argument (for example `ImperfectDataTest` constructs `RachanaProperties` directly).

**3. Put it in `drishti-server/src/main/resources/application.yaml`** with an environment override and a comment saying what it does.
The file has one `drishti:` block: add `notices:` inside it (for example after `identity:`), not a second `drishti:` key, which YAML
rejects as a duplicate:

```yaml
drishti:
  notices:
    max-length: ${DRISHTI_NOTICE_MAX_LENGTH:500}   # longest notice text admins may set
```

The default in the YAML and in the record must agree: the record's default applies when the key is absent (`SearchProperties` keys are
not in the bundled file at all).

**4. Document it** in [CONFIGURATION.md](../admin/CONFIGURATION.md), in the section for its prefix: key, default, meaning, and the
environment variable (the console test `test_docs_settings.py` fails when a key of `application.yaml` is missing there).

**5. Test it** where the behaviour is, with the property set in the test: `@SpringBootTest(properties = "drishti.notices.max-length=40")`,
or in the identity contract by adding `props.put("drishti.notices.max-length", "40");` in `open()` (40, so the previous recipe's
23-character notice still fits) and a test beside `noticesRoundTripOnEveryDatabase`:

```java
    @Test
    void aNoticeLongerThanTheConfiguredMaximumIsRefused() {
        assertThatThrownBy(() -> bean(NoticeStore.class).set("long", "x".repeat(41), true, "drishti-dev-admin"))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("at most 40");
    }
```

Then run `./mvnw -o -pl drishti-identity -am install` and `./mvnw -o test -pl drishti-server` (the server starts with the new property
bound).

**In the console** the pattern is the same YAML: add the key to `drishti-console/config/application.yaml` as
`${DRISHTI_SOMETHING:default}` and read it with `settings.get("section.key", default)`. Every console key can also be set as
`DRISHTI_CONSOLE__SECTION__KEY` without a named variable.

#### 5.8 Add a console page

**Goal:** `/sources`, a page listing the connectors the server runs, their kinds and health.

**1. The route**, `drishti-console/routes/source_routes.py`. Pages call the server through `request.app.state.backend` with
`ident(request)`, turn a `BackendError` into a message, and render with `render`, which adds the signed-in user, packs, business date
and settings to the template context:

```python
"""Sources at a glance: every connector the server runs, the kinds it serves and its health."""
from __future__ import annotations

from fastapi import APIRouter, Request

from core.backend import BackendError
from routes.common import ident, render

router = APIRouter(include_in_schema=False)


@router.get("/sources")
async def sources(request: Request):
    try:
        data, error = await request.app.state.backend.sources(ident(request)), None
    except BackendError as e:
        data, error = {"sources": [], "failures": {}}, e
    return render(request, "sources.html", sources=data.get("sources", []), failures=data.get("failures", {}),
                  error=error, screen="sources")
```

**2. Register it** in `create_app` in `drishti-console/core/app.py` (import `source_routes` beside the other routers and
`app.include_router(source_routes.router)`), and add `"/sources"` to the `PROTECTED` tuple in the same file. **A path that is not in
`PROTECTED` is public** when sign-in is on. To list it in the top bar, add an entry to the menus in
`web/templates/terminal/_topbar.html`.

**3. The template**, `drishti-console/web/templates/sources.html`, extends `base.html` like the other terminal pages (the admin and
workspace templates are good models):

```jinja
{% extends "base.html" %}
{% block title %}Sources · {{ PRODUCT }}{% endblock %}
{% block body_class %}terminal{% endblock %}
{% block nav %}{% include "terminal/_topbar.html" %}{% endblock %}
{% block content %}
<div class="adm">
  <h1>Sources</h1>
  {% if error %}<p class="pnl-err" role="alert">{{ error.code }} {{ error.detail }}</p>{% endif %}
  <label for="srcFilter" class="visually-hidden">Filter</label>
  <input id="srcFilter" class="studio-in mono" placeholder="Filter by name or kind" data-src-filter>
  <div class="tbl-wrap"><table class="tbl">
    <thead><tr><th>Source</th><th>Kinds</th><th>Live</th><th>Health</th></tr></thead>
    <tbody>{% for s in sources %}<tr data-src-row="{{ s.name }} {{ s.kinds | join(' ') }}">
      <td class="mono">{{ s.name }}</td><td class="mono">{{ s.kinds | join(', ') or 'any' }}</td>
      <td>{{ 'yes' if s.live else 'no' }}</td><td class="mono">{{ s.health }}</td></tr>
    {% else %}<tr><td colspan="4">No sources.</td></tr>{% endfor %}</tbody>
  </table></div>
</div>
{% endblock %}
{% block scripts %}<script src="/static/js/command.js?v={{ ASSET_V }}"></script><script src="/static/js/sources.js?v={{ ASSET_V }}"></script>{% endblock %}
```

Jinja autoescapes, so values from the server are safe to print. `ASSET_V` is the asset version plus a fingerprint of `static/js` and
`static/css`: always add `?v={{ ASSET_V }}` to your own script and style URLs. A table must not scroll the page sideways at 390 px
(`tbl-wrap` scrolls it inside itself), and every control needs a 44 px target on a phone.

**4. The script**, `drishti-console/web/static/js/sources.js`. No inline script: the page wires behaviour by `data-*` attributes, and
the script is an IIFE in strict mode like the others:

```js
/* Sources page: filters the table as you type. */
(function () {
  'use strict';
  var box = document.querySelector('[data-src-filter]');
  if (!box) { return; }
  var rows = document.querySelectorAll('[data-src-row]');
  box.addEventListener('input', function () {
    var q = box.value.trim().toLowerCase();
    rows.forEach(function (r) { r.hidden = q !== '' && r.getAttribute('data-src-row').toLowerCase().indexOf(q) < 0; });
  });
})();
```

**5. The test.** The `client` fixture in `drishti-console/tests/conftest.py` builds the real app with a `FakeBackend` in place of the
server. Add the method your page calls to `FakeBackend`:

```python
    async def sources(self, ident=None):
        return {"sources": [{"name": "trade-endur", "version": "1.0", "kinds": ["trade"], "live": False, "search": True,
                             "reverseLookup": True, "health": "UP (engine: native)"},
                            {"name": "credit-store", "version": "1.0", "kinds": ["netting-set"], "live": False,
                             "search": True, "reverseLookup": True, "health": "UP (engine: native)"}],
                "failures": {}}
```

and a test file, `drishti-console/tests/test_sources.py`:

```python
"""The sources page lists connectors and their kinds, under the CSP."""


def test_sources_page_lists_connectors(client):
    r = client.get("/sources")
    assert r.status_code == 200
    assert 'data-src-row="credit-store netting-set"' in r.text
    assert "/static/js/sources.js?v=" in r.text
    assert "script-src 'self'" in r.headers["content-security-policy"]
```

Run the console tests:

```bash
drishti-console/.venv/bin/python -m pytest -q -p no:cacheprovider drishti-console/tests/test_sources.py -k connectors
drishti-console/.venv/bin/python -m pytest -q -p no:cacheprovider -n 4 --dist loadfile drishti-console/tests        # the whole suite
```

(`-p no:cacheprovider`: no `.pytest_cache` folder.) `test_assets_policy.py` will fail if the template gains an inline script, handler or
style, or an external URL.

##### Regenerating the favicon and app icons

The browser-tab icon, the home-screen icons and the app manifest's icons are all the logo mark (the amber braces and eye of
`web/static/img/drishti-mark.svg`), filling a navy tile with the eye's outline in white, so they read on light and dark tab bars. They
are generated, never drawn by hand, into `drishti-console/web/static/img/icons/`:

| File | Size | Used by |
|---|---|---|
| `favicon.ico` | 16, 32 and 48 px in one file | `/favicon.ico` (browsers ask for it on their own) and `<link rel="icon">` |
| `favicon.svg` | scalable | `<link rel="icon" type="image/svg+xml">` in `base.html`; modern browsers prefer it |
| `favicon-16.png`, `favicon-32.png`, `favicon-48.png` | as named | the `.ico`; 16 and 32 px use heavier strokes so the mark survives at tab size |
| `apple-touch-icon.png` | 180 px, square corners | iOS "Add to Home Screen" (iOS rounds the corners) |
| `icon-192.png`, `icon-512.png`, `icon-maskable-512.png` | as named | `manifest.webmanifest`; the maskable one keeps the mark inside the 80 % safe zone |

```bash
python3 tools/make_icons.py            # writes every file above (needs Inkscape and ImageMagick)
python3 tools/make_icons.py --check    # exit 1 when an icon is missing or favicon.svg is not what the script makes
```

Change the colours, the fill or the stroke weights in the script, never in the files. `tests/test_icons.py` checks that the files
exist, that `/favicon.ico` answers, and that the pages and the manifest link them.

##### Regenerating the element stylesheet

`<drishti-view>` (Drishti Elements, `docs/architecture/CLIENT_RENDERER.md`) styles its Shadow DOM with a **generated** sheet, not the
console's own: `drishti-console/web/elements/drishti-view.css` and `drishti-view.manifest.json`. It is the console's tokens, theme,
terminal, layout and gradients sheets with `:root`, `html` and `body` rewritten to `:host`, no `@font-face` (the element registers the
icon font with the document's `FontFace` API), and of Bootstrap Icons only the glyphs that `templates/_macros/panels.html`,
`templates/_macros/view.html` and the scripts that run inside the element name. Regenerate it after changing any of those sheets, a
macro, `bi.css` or the report styles, or an icon in such a script:

```bash
python3 tools/elements_sheet.py            # write the sheet and its manifest; prints size and glyph count
python3 tools/elements_sheet.py --check    # exit 1 when the committed files are stale
python3 tools/elements_sheet.py --icons    # which glyph is named where
```

The output is reproducible and its version is the first 12 hex digits of its hash (served at
`/embed/v1/elements/<version>/drishti-view.css`, cacheable for ever). `tests/test_element_sheet.py` fails when the committed sheet is
stale or a macro uses a glyph the sheet lacks: run the tool and commit the two files. A name that is not a Bootstrap Icons glyph makes
the tool fail and says where it is named.

**Writing element CSS: width rules become container rules.** Write responsive rules in the console's sheets as usual
(`@media (max-width: 640px) { ... }`). The generator rewrites every width-based `@media` rule into
`@container drishti (max-width: 640px) { ... }`, so an embedded view answers to the width of its **element** (the host element is a
size container, `container: drishti / inline-size`), not to the host page's viewport; `:host` inside such a rule becomes `.view` (a
container cannot style itself) and `vw` becomes `cqw`. Non-width media (`prefers-color-scheme`, `prefers-reduced-motion`, `print`,
`hover`, `pointer`) are kept. Do not write `@container` by hand in the console's sheets, and do not use `100vw` or `vh` for something
inside the element's tree where the element's own size is meant. Element-only rules (finger-sized controls, the About sheet's width)
live in `ELEMENT_ADDENDUM` in `core/element_sheet.py`. `test_element_sheet.py` fails if a width `@media` rule is left in the sheet.

---

#### 5.8a The shared multi-select dropdown

`static/js/multiselect.js` (vendored with the console, no library) is the one multi-select the console draws: a `<details>` whose
`<summary>` shows "All", the value, or "3 selected: ABS, REPO, +1", and whose pop-up holds a search box, *Select all* / *Clear* and a
labelled group of **real checkboxes** (so form submission, keyboard and screen readers are the browser's own; 44 px rows on touch; theme
tokens in `css/controls.css`). Two callers: the query-by-form renderer marks every enum field with more than 3 values `data-ms`
(`terminal/form.html`; up to 3 stay a checkbox row, so the renderer decides by option count and no pack is hard-coded) and
`multiselect.js` wraps it on load, keeping the checkboxes' names and so the criteria encoding; and `controls.js` builds the
`multi-select` control from `window.drishtiModules.multiselect.decorate(details, {label, boxes, onChange})`. Inside `<drishti-view>` it is
one of the allow-listed enhancer scripts (`routes/embed_routes.py` `ENHANCERS`, `web/embed/assets.js`), loaded before `controls`.
Tests: `test_form_browser.py` (dropdown, search, keyboard, URL, phone), `test_controls_browser.py`.

#### 5.9 Write a view enhancer (the `init(root)` contract)

A script that makes a rendered view interactive (sorting, charts, popovers) must work on the console's page and inside a
`<drishti-view>` Shadow DOM, from one copy of the code (docs/architecture/CLIENT_RENDERER.md › Drishti Elements: the design, section 12). The contract:

- Wrap the code in `function init(root, options)`; `root` is `document` or a `ShadowRoot`. Query with `root.querySelector*` and
  `root.getElementById`, never `document.*` for view content; use `root.activeElement` and `root.elementFromPoint`.
- Watch for new panels with a `MutationObserver` on `root === document ? document.body : root`; listen for keys and events on `root`
  (never on `document` or `window` when root is a shadow root), so a host page's keys are untouched.
- Take everything environmental from `options`: `fetch(url, init)` and `url(path)` (the embed API differs from the console's paths),
  feature switches (`save`, `exports`, `guideKey`), callbacks (`open`, `reload`). No `location`, no `localStorage` keys that collide,
  no fixed positions computed against the window without the host's origin (`root.host.getBoundingClientRect()`).
- Read theme tokens from `getComputedStyle(root.host || document.documentElement)`; size with a window `resize` for the document and a
  `ResizeObserver` on `root.host` for a shadow root.
- Return `{ dispose }` (remove listeners and observers). Register `window.drishtiModules.<name> = { init }`; start `init(document)`
  unless `document.currentScript` has `data-manual`; publish console globals only in that case.
- Add the script to `ENHANCERS` in `routes/embed_routes.py` and to `boot` in `web/embed/enhancers.js` (and `ENHANCERS` in
  `web/embed/assets.js`); extend `test_embed_elements_browser.py` to prove it inside the element. The console's own browser tests must
  pass unchanged.

#### 5.10 Retry and live-reload utilities

Two small, Spring-light pieces in `drishti-common` serve every area that keeps something running from files (packs, connectors, AI
providers, MCP servers, BI artifacts). Operator view: [OPERATIONS.md](../admin/OPERATIONS.md#retries-what-failed-is-tried-again-by-itself).

**Retrying what failed** (`com.ash.drishti.common`, no Spring, virtual-thread friendly, locks are `ReentrantLock`, nothing blocks under
`synchronized`):

| Class | Use |
|---|---|
| `RetryPolicy.of(env::getProperty, "bi")` | reads `drishti.retry.*` then the area's `drishti.retry.bi.*` (`enabled`, `initial`, `factor`, `max`, `jitter`, `permanent-after`); `baseDelay(attempt)`, `delay(attempt, permanent, random)`; `looksPermanent(error)` classes refused credentials and bad settings |
| `RetryTracker` | the state of one failing thing: `failed(error, permanentHint)` plans the next try, `due()`, `retryNow()`, `succeeded()`, `status()` |
| `RetryBoard` | the failing things of one area by name: `failed(key, error, hint)` (logs failures 1, 2, 4, 8, ...), `succeeded(key)` (logs the recovery, returns true if it had failed), `due()`, `retryNow(key)`, `retryAllNow()`, `statuses()`, `forget(key)`, and a `Listener` for the audit log; `RetryBoard.describe(status, now)` is the JSON the Admin pages read |

The owner supplies the timer: call `board.due()` from a scheduler, a watcher pass or a request, try each key, and report the outcome
with `failed` / `succeeded`. Do the attempt outside any lock the board does not own. The console twin is `core/retry.py`
(`RetryPolicy.of(settings, "ai")`, `RetryBoard`).

**Following settings without a restart** (`LiveSettings`): a bean built from settings a pack contributes implements
`Runnable prepare(Environment candidate)`. The pack reloader calls it with a *candidate* environment (the current one with the new pack
settings in it); `prepare` binds, validates and builds the new state and returns the commit that swaps it in. Throw from `prepare`, never
from the commit: a throw refuses the whole reload and nothing has changed. The commit only assigns a `volatile` reference (or a
copy-on-write structure), so a reader sees the old state or the new one, never a mixture; take one local snapshot per call when you read
the state more than once. Examples: `ReferenceCatalog`, `SearchProperties`, `RoleCatalog`, `Formats`, `AboutCatalog`, `SourceRouter`,
`SourceRegistry` (plain plugins' settings).

### 6. Testing strategy

| Layer | Where | How | Examples |
|---|---|---|---|
| Unit | every module, `src/test/java` | JUnit 5 and AssertJ, plain constructors, no Spring | `DataNodeTest`, `ShapeFingerprinterTest`, `SearchQueryTest`, `DocumentDiffTest`, `FormatsTest`, `SutraParserTest`, `IdColumnTest` |
| Property-based | `drishti-rachana`, `drishti-engine` | plain JUnit 5 `@ParameterizedTest` over seeded generators (`SplittableRandom`/`Random`, base seed overridable with `-Ddrishti.test.seed`); failures print the case index, seed and input, with a greedy shrink | `ElTest` (random arithmetic), `ShapePropertyTest` |
| Concurrency | api, engine, identity, server | many threads on one object, then invariants | `HitIndexConcurrencyTest`, `TopicHubTest`, `UserServiceConcurrencyTest`, `LiveStreamSlotsTest` |
| Contract | `drishti-testkit` and subclasses | one abstract test class, one subclass per implementation | `DatedSourceContract` (Delta, Delta on S3, PostgreSQL table mode, Aerospike, Cassandra, ScyllaDB), `MessageSourceContract` and `BrokerOutage` (ActiveMQ, RabbitMQ), `IdentityStoreContract` (SQLite, PostgreSQL) |
| Spring and HTTP | `drishti-server` | `@SpringBootTest` + `@AutoConfigureMockMvc`, properties set per test class | `ApiTest`, `SecurityTest`, `CacheAdminTest`, `IdentityApiTest`, `WorkspaceApiTest`, `StructuredSearchTest`, `LiveTest` |
| Whole pipeline | `drishti-server` | `ViewPipeline` from the context, real packs | `ViewPipelineTest` (the finance pack's four mockups, value by value), `BankingPacksTest`, `DomainPacksTest`, `SamplePacksTest`, `ImperfectDataTest`, `BusinessDateTest` |
| Multi-source trades | engine, server | the union of connectors, read, searched and aggregated as one | `UnionConnectorsTest`, `UnionTableTest` |
| BI engine | `drishti-server` `bi.*` | a small generated lake (`TestLake`), the real DuckDB engine | `QueryEngineTest` (layers, rollups, caching, a data load, a rebuilt source date, row rules and masks for several readers, SQL injection attempts), `DatasetRefresherTest`, `LiveTablesTest`, `TimeIntelligenceTest` |
| Real services | plugins, identity | Testcontainers, skipped without Docker ([§2.3](#23-tests-that-need-docker)) | `PostgresIdentityStoreTest`, `AerospikeSourcePluginTest`, `RabbitMqOutageTest` |
| Repository rules | `drishti-it`, `tools/` | ArchUnit and file walks | `ArchitectureRulesTest`, `LicenseHeaderTest`, `SourceFileSizeTest`, `NoBlockingUnderSynchronizedTest`, `tools/test_no_hidden_names.py` |
| Documentation | rachana, server, console | the docs are parsed as code | `DocumentedSutrasTest` (every complete Sutra, a ```` ```yaml ```` block starting `rachana:`, in `docs/`, `drishti-console/web/guides/` and pack guides), `RachanaReferenceExampleTest`, `PackSutrasTest`; console `test_docs_*.py` (endpoints, error codes, settings, plugins, placeholders), `test_help*.py`, `test_tutorials.py`, `test_guide_images.py`, `test_tools_guide.py` |
| Performance gate | `drishti-server` | timed in the test | `ViewPipelineTest.warmViewsStayWellUnderFiftyMillisecondsAtP99`: 300 warm-up views, then 2000 timed; p99 must be under 50ms and the layout cache hit rate above 0.99 |
| Micro-benchmarks | `drishti-benchmarks` | JMH, run by hand | `HotPathBenchmark` ([PERFORMANCE.md](../admin/PERFORMANCE.md) has the commands) |
| Console | `drishti-console/tests` | pytest with `FakeBackend`; fixtures in `tests/fixtures/` are ViewModels captured from the real server | `test_terminal.py` (every panel kind with broken data), `test_assets_policy.py` (no CDN, no inline code, Python size), `test_contrast.py` (theme contrast), `test_help.py` (every catalogued guide renders) |
| Python tools | `tools/` | `unittest` | `tools/samplegen/test_samplegen.py`, `tools/lake/test_build.py`, `tools/lake/test_demo.py`, `tools/lake/test_maintain.py`, `tools/testdata/test_testdata.py` |
| Generated content | `tools/packgen`, pack tools | `--check` | run by the drill |
| In the browser | your machine (`drishti-console/tests/*_browser.py`) | Playwright with Chromium; skipped when not installed | `test_live_tabs_browser.py`, `test_page_widths_browser.py` (no sideways scroll at 390, 1600 and 2560 px; images fit), `test_workspace_keys_browser.py`, `test_calc_browsers.py`. Install with `uv pip install -r drishti-console/requirements-test.txt` and `drishti-console/.venv/bin/python -m playwright install chromium`. Run **one browser test file at a time** on a laptop. They do not replace a look by hand: after a UI change, open the page in the running console in at least one dark and one light theme and on a phone width, use the keys (F-keys, `/`, `Alt+←`, table keys), watch a live view tick, and check the browser console for CSP violations. |

Habits that keep the suite fast and reliable:

- Turn the demo plugin's ticking off in Spring tests that do not test live updates:
  `"drishti.sources.plugins.demo.settings.ticking=false"`.
- Name the packs a test needs (`"drishti.packs.enabled=trading"`) rather than relying on the default. The build sets
  `drishti.packs.extra-dirs` to `examples/packs` and the default to `finance` for the server tests, so the tests written against the
  retired `finance` and `logistics` packs keep them as fixtures; a new test should name shipped packs and UPPER_SNAKE fields.
- Server tests write only into their fork's run folder, `target/test-run/fork<N>/` (`drishti.test.dir`, `drishti-server/pom.xml`),
  which the fork removes when it ends (`TestRun` closes the Spring test contexts first), so a run leaves nothing in `target/` but the
  build's output and never touches `./data`. A test names its folders `${drishti.test.dir}/...` in its properties, seeds a folder before
  its context starts with `TestRun.dir(name)`, and takes a temporary folder from `TestRun.temp(prefix)`; a test without Spring uses
  JUnit's `@TempDir`. Never write under a bare `target/...` path: per-test folders there survive every run until `mvn clean` (2.7.0
  found twelve million files, each content store importing the seals a shared collaboration folder had piled up); a Python test that runs `lake build` or `lake demo` sets `DRISHTI_DATA_DIR` or `DRISHTI_BI_STORE` to a temporary folder and
  passes `--server` (or `--no-signal`) so it never writes into the repository's `data/` or signals a real server.
- Keep local test data small (a few thousand trades): a laptop is not a scale-test machine.
- Prefer one assertion chain that reads like the behaviour (`assertThat(p99).as("warm p99 ms").isLessThan(50)`).
- Name tests as sentences: `aMissingFigureIsAnEmptyPanelNotAnError`.

---

### 7. Debugging

#### 7.1 Where to look

With sign-in on (the run configurations), every `/api/v1` call needs `-H "Authorization: Bearer <token>"`: a personal API token
([GETTING_STARTED.md, Step 10](GETTING_STARTED.md#step-10--a-personal-api-token-for-the-command-line)). With it off, add
`-H 'X-Drishti-User: ash'` to act as a user. The answers below were captured on the demo lakes.

| Question | Ask |
|---|---|
| Is the server up? | `curl -s localhost:18480/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}` (`/actuator/health/liveness` and `/readiness` for probes; open even with sign-in on) |
| Is the console up, and can it reach the server? | `curl -s localhost:17480/healthz` (the process: `{"status":"UP","pythonRuntime":…}`) and `curl -s localhost:17480/readyz` (`503` while the server is unreachable or refuses the console) |
| Which packs, sources and problems? | `curl -s localhost:18480/api/v1/admin/health \| python3 -m json.tool`: version, uptime, heap, every source with status, health (`UP (engine: native)`), kinds, live/dated/search and read counts, `failedToStart`, pack problems, connectors, BI. *Admin → Health* shows the same; `drishti.py doctor --server … --token-file …` sums it up. |
| What does the server return for a view? | `curl -s localhost:18480/api/v1/views/trade/MX-20000001 \| python3 -m json.tool`. Add `-H 'X-Drishti-As-Of: 2026-10-15'` for a past date; `drishti.py view get trade/MX-20000001` prints it as text |
| What did the source send? | `F9` in the view, or `curl -s localhost:18480/api/v1/entities/trade/MX-20000001/raw` (provenance `"source":"trade-murex"`; masked fields read `•••` for roles without `raw`) |
| Which Sutra, how long? | The view JSON's `provenance.layout` (`Sutra irs-fixfloat v1 + inference`, or `inference only`) and `timings` (`fetch`, `layout`, `links`, `bind`, `total` in ms) |
| What would inference do on its own? | `curl -s localhost:18480/api/v1/studio/inferred/trade/MX-20000001` returns the inferred Sutra as YAML (`text/yaml`, starting with a comment, then `rachana: 1`, `sutra: trade-custom`) |
| Is a Sutra broken? | `curl -s localhost:18480/api/v1/sutras/problems` (`{}` when none); see [runbooks/sutra-broken.md](../admin/runbooks/sutra-broken.md) |
| Which sources run? | `curl -s localhost:18480/api/v1/sources` (and `failures`); `drishti.py connector list` for the connector files |
| What changed between dates? | `curl -s localhost:18480/api/v1/history/trade/MX-20000001/diff` |
| How are live streams doing? | `curl -s localhost:18480/api/v1/health/live` → `{"streams":0,"topics":2000,"frames":0,"p50Ms":0.0,"p99Ms":0.0}` |
| What did a BI question do? | the answer's `meta` (layer, tables, cache, time, generation) or `explain: true` in the model query; *Admin → BI* and the model's page |
| What is in the BI store? | `<data>/bi-store/columns/<dataset>/<table>/date=<d>/meta.json` (rows, how it was read, the source version) |
| Metrics | `/actuator/metrics/drishti.view`, `/actuator/prometheus` (`drishti_view_seconds`, `drishti_live_*`) |
| Every endpoint | `/api/docs` (OpenAPI JSON) and `/api/docs/ui` (Swagger UI). With security on, `/api/docs` needs a token and `/actuator` (except health) an admin token or `DRISHTI_METRICS_TOKEN`. |

#### 7.2 Logs

Both programs log to standard output. The server logs each plugin it starts (`source plugin started: …`), plugins left idle (`…
installed but not configured …`), the connector files it generated (`connectors: connector file trade-endur.yaml generated from the
template of pack 'trading'`), failures with stack traces, the identity database it opened (`identity database ready: …`), a lake rebuilt
while it was stopped (`The lake was rebuilt while the server was stopped …`), and every 5xx answer (`request failed: …`). Raise the
level for Drishti's packages with Spring's usual environment form:

```bash
LOGGING_LEVEL_COM_ASH_DRISHTI=DEBUG java -jar drishti-server/target/drishti-server-2.9.6-exec.jar
```

The console runs Uvicorn with access logs off (`access_log=False` in `run_drishti_web.py`). The run configuration **Drishti server
(debug-friendly)** is the one to start with **Debug** in IntelliJ: sign-in off (no token to mint while you step through), a 1 GB heap, port
18580 and the QA data folder `data/qa/data`, so it runs beside a normal server ([GETTING_STARTED.md (IDE)](GETTING_STARTED.md#running-from-intellij-idea-and-pycharm)).

#### 7.3 Debugging a live view

1. Does the view say **Live** in the top bar? A picked business date is static: no stream is opened. On the demo lakes with the demo
   source off nothing ticks: start with `DRISHTI_DEMO_ENABLED=true` and open a live sample (a curve, an FX spot, a trade valuation,
   [DEMO_CONNECTOR.md](../connectors/DEMO_CONNECTOR.md)).
2. `provenance.live` in the view JSON must be `true`; only live sources (`SourceCapabilities.live`) tick.
3. Watch the server stream directly; you should see an `event:view` and then `event:frame` lines:

   ```bash
   curl -sN localhost:18480/api/v1/views/ir-curve/CRV-USD-BASIS/stream -H "Authorization: Bearer $DRISHTI_TOKEN" | head -c 600
   ```

4. There should be exactly one `/api/channel?s=…` request for the whole browser, staying open: in the network panel of the SharedWorker
   (`chrome://inspect/#workers`), or of the leader tab when there is no SharedWorker. `DrishtiChannel.transport()` in a tab's console
   says which (`shared-worker`, `leader`, `follower`).
5. If ticks arrive but the page does not change, check the browser console for errors in `live.js`.

For a live BI visual: its chip says **Live**, **Listening** (following, nothing has changed: a daily lake) or why it was refused; the
server's `GET /api/v1/bi/mq/stream` sends `answer` and `status` events (section 4.5).

---

### 8. Common pitfalls

1. **Starlette cancel scopes in streaming responses.** In `drishti-console/routes/api_routes.py`, upstream work for `/api/channel` runs
   in tasks created with a **fresh** `contextvars.Context` (`detached()`), carrying only the business date and "known at". A task started
   from the request's context inherits Starlette's (anyio's) cancel scope, which, once the request has ended, cancels every `await` in it
   for good: clean-up never completed, the console's connections to the server stayed open, the pool ran out, and the UI froze. End
   streams by **closing the upstream response**, never by cancelling a task that is reading it. Follow the same pattern in any new
   streaming route.
2. **Stale scripts after a change.** `ASSET_V` (in `drishti-console/core/app.py`) is `ASSET_VERSION` plus a hash of the names, sizes and
   modification times of `web/static/js/*` and `web/static/css/*`, computed **once at start-up**. Restart the console after editing
   scripts or styles, or browsers keep the cached file. Files you add elsewhere (images, vendor upgrades) are not fingerprinted; change
   their name or bump `ASSET_VERSION`.
3. **Editing generated files.** Generated `pack.yaml`, Sutras, samples, guides, test data and `help.yaml` are overwritten by the next
   generator run, and the drill's `--check` rejects a hand edit. The banking packs' generated guides copy their header from the first 15
   lines of `docs/guides/RACHANA_REFERENCE.md` (`tools/packgen/banking/make_docs.py`), so editing that document's header makes them
   stale too. Connector files the server generated are regenerated when the pack's template changes, unless you edited them.
4. **Sutras in documentation are compiled.** `DocumentedSutrasTest` parses every ```` ```yaml ```` block whose first line after any
   comments is `rachana:` in `docs/`, `drishti-console/web/guides/` and pack guides, and compiles its expressions. Such a block must be a
   complete, valid Sutra. A fragment (a panel, a strip) is a `yaml` block without `rachana:`; a block that is deliberately abridged
   contains `…` and is skipped.
5. **Environment variables leak into tests.** See [§2.2](#22-maven-commands): unset `DRISHTI_*` before running Maven.
6. **A page that is public by accident.** New console paths must be added to `PROTECTED` in `drishti-console/core/app.py`. `/t` and `/s`
   are matched exactly (they are prefixes of public paths such as `/static`); the rest by prefix.
7. **Inline code under the CSP.** `onclick=`, `<script>…</script>` and `style="…"` are blocked by the browser and rejected by
   `test_assets_policy.py`. Use a script file and `data-*` attributes.
8. **A plugin that starts by surprise.** Plugins are enabled by default when they are on the class path. Disable them in
   `application.yaml` until configured, or throw `PluginNotConfigured` from `start`.
9. **`synchronized` around I/O** pins a virtual thread to its carrier. Use `ReentrantLock` (see
   [§3.4](#34-object-oriented-concurrent-and-configuration-driven)).
10. **Adding a record component** to a `@ConfigurationProperties` record breaks every direct constructor call in tests; a record with two
    constructors needs `@ConstructorBinding` on the canonical one.
11. **SQLite hides schema mismatches.** Hibernate validates only on PostgreSQL. Run the identity contract on PostgreSQL (Docker) after
    changing an entity or a schema file.
12. **Packs that clash.** Two unrelated packs defining the same mnemonic, role, route or connector stop the server. The retired `finance`
    pack and the seven both define `TRD` and `CRV`; never enable them together.
13. **Layouts that do not change.** The layout cache is keyed by the Sutra object, kind and data shape; a hot reload clears it. If you
    changed inference code or semantic hints instead, restart, or purge the engine cache from *Admin → Caches*.
14. **A test that reads the repository** (`DocumentedSutrasTest`, `ImperfectDataTest`, the `drishti-it` rules) resolves paths relative
    to its module (`../docs`, `../config/packs`). Run Maven from the repository root.
15. **A hidden file or folder.** Drishti makes none, in the repository or at run time: a person must be able to see what is where. Name a
    new file or folder with a visible name (`history/`, `generated/`, `drishti-temp-folder.txt`, a temp file `name-xxxx.tmp`, never
    `.history`). Everything the server, the console and the tools write goes under the data folder (`DRISHTI_DATA_DIR`, default
    `./data`), never the repository root. When a name changes, read the old one once and rename it with a log line (`LegacyNames` in
    Java; see `packregen._gen_dir` in Python), never lose data. `tools/test_no_hidden_names.py` fails on a new hidden name or a tracked
    one.
16. **camelCase in a pack.** The shipped packs' documents, Sutras, BI files, samples and test data are UPPER_SNAKE (`TRADE_ID`,
    `PRODUCT_TYPE`, `LEGS[].CASH_FLOWS[].RESETS`). A Sutra path, a DEL measure or a search written in camelCase finds nothing (a search
    says `DRS-4004 no trade has a field …; did you mean …`). The retired example packs are camelCase; do not copy their names.
17. **A test that signals the user's server.** `lake demo` and `lake build --signal` talk to `DRISHTI_SERVER`, default
    `http://localhost:18480`. A test or script must pass its own `--server` (or `--no-signal`) and its own data folder.

#### Why these few names start with a dot

Only names an external tool looks for by exactly that name keep the dot:

| Name | Why |
|---|---|
| `.gitignore`, `.gitattributes`, `.git/` | Git |
| `.dockerignore` | Docker |
| `.editorconfig` | editors |
| `.java-version` | version managers (jenv, SDKMAN) |
| `.mvn/` | the Maven wrapper |
| `.github/` | GitHub |
| `.idea/` | IntelliJ IDEA at the root, PyCharm in `drishti-console/` (our run configurations are in the visible `run-configs/` and `drishti-console/run-configs/`) |
| `.venv/` | the Python convention for a virtual environment (git-ignored) |
| `.claude/` | Claude Code |

Everything else is visible. The user's own `~/.cache/drishti*` (downloads of the fetch tools) follows the operating system's convention
and is outside the repository.

---

### 9. Releasing

Releases are numbered `MAJOR.MINOR.PATCH` and tagged `vX.Y.Z` on `main` (`git tag` lists `v1.0.0` …). The release commit for 1.9.0
(`Release 1.9.0`) shows every file a bump touches.

1. **Start clean on `develop`** with everything for the release merged and drilled.
2. **Bump the version** everywhere it is written. Find the old version first:

   ```bash
   git grep -n -F '2.5.0' -- '*pom.xml' drishti-console/core/app.py deploy README.md docs
   ```

   | File | What changes |
   |---|---|
   | `pom.xml` and every module's `pom.xml` (including `plugins/*/pom.xml`) | the project `<version>` and each module's `<parent><version>` |
   | `drishti-console/core/app.py` | `ASSET_VERSION`, so browsers fetch the new scripts |
   | `deploy/docker/compose.yaml`, `deploy/docker/server.Dockerfile`, `deploy/docker/console.Dockerfile` | image tags `drishti-server:<version>`, `drishti-console:<version>` |
   | `deploy/docker/ecs/*.Dockerfile`, `deploy/aws/env.example` (`DRISHTI_VERSION`) | the ECS image tags `drishti-server-ecs`, `drishti-console-ecs`, `drishti-loader-ecs` |
   | `README.md`, `docs/*.md` | the jar name `drishti-server-<version>-exec.jar` and versions in examples |

   Leave historical entries (old changelog sections) alone.
3. **Write the release text.** Add a section at the top of `CHANGELOG.md` (`## X.Y.Z — <headline> (<date>)`, then what changed,
   grouped, with the settings and endpoints involved) and of `RELEASE_NOTES.md` (`# Drishti X.Y.Z — release notes`, a short, user-facing
   summary; the previous release's heading becomes `# Previous release: Drishti …`). Add upgrade notes when the identity schema,
   configuration keys, on-disk formats or pack attribute names change.
4. **Verify locally on a fresh clone.** Clone, build the jar, `lake demo`, start the server and the console as
   [GETTING_STARTED.md](GETTING_STARTED.md) says, and follow the tutorials; `curl -s localhost:18480/api/v1/about` reports the
   `version` (on 2.6.7: `"version":"2.6.7"`).
5. **Commit** as `Release X.Y.Z` on `develop`.
6. **Drill:** `tools/drill.sh`. Nothing reaches `main` unless the whole build, every test, the console tests, the header check and the
   generator checks pass.
7. **Tag `main`** with an annotated tag and push it:

   ```bash
   git tag -a vX.Y.Z -m "Drishti X.Y.Z: <headline>" main
   git push origin vX.Y.Z
   ```

8. **Images (optional):** `docker build -f deploy/docker/server.Dockerfile -t drishti-server:X.Y.Z .` and
   `docker build -f deploy/docker/console.Dockerfile -t drishti-console:X.Y.Z .` from the repository root, after building the jar.
   [OPERATIONS.md](../admin/OPERATIONS.md) covers deployment and upgrades.
