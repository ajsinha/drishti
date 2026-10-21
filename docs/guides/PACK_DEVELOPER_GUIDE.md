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
seven shipped packs as the reference.

Who reads what:

| You are | Read |
|---|---|
| a user or administrator: which packs ship, turning them on and off, who sees which, the demo lakes | [PACKS.md](PACKS.md) |
| writing a pack | this guide |
| writing the Sutras of a pack | [Sutra guide](SUTRA_DEVELOPER_GUIDE.md), every key in [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md), the workbench in [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md) |
| connecting a pack to a database, a lake or a queue | [CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md), [CONNECTOR_DEVELOPER_GUIDE.md](../connectors/CONNECTOR_DEVELOPER_GUIDE.md) and the per-connector pages they list |
| turning JSON files or a schema into a pack without writing files by hand | [tutorial 5](../tutorials/05-build-a-pack.md), [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md), [Generating a pack from JSON Lines](#generating-a-pack-from-json-lines) |

Contents:

1. [What a pack is made of](#what-a-pack-is-made-of)
2. [The worked example: a help-desk pack](#the-worked-example-a-help-desk-pack)
3. [Build it, step by step](#build-it-step-by-step) (steps 1 to 17)
4. The topics, each in depth: [`pack.yaml` key by key](#packyaml-key-by-key) · [the lake layout](#large-kinds-the-lake-layout) ·
   [several source systems for one kind](#several-source-systems-for-one-kind-the-trading-pack) ·
   [derived kinds](#derived-kinds-entities-computed-from-other-kinds) · [samples](#samples) · [deep documents](#modelling-deep-documents-legs-cashflows-and-fixings) ·
   [vocabulary](#vocabulary-labels-formats-and-inference-hints) · [About text](#about-text-and-glossary) · [guides and help](#guides-and-help) ·
   [roles and field masks](#roles-and-field-masks) · [Calc snippets](#calc-python-snippets) · [inheritance](#inheritance)
5. Around the pack: [tests](#testing-a-pack) · [generators](#how-the-shipped-packs-are-generated) ·
   [from JSON Lines](#generating-a-pack-from-json-lines) · [versioning](#versioning-a-pack) ·
   [the signed registry](#a-signed-pack-registry-publishing-and-installing) ·
   [loading while the server runs](#loading-a-pack-while-the-server-runs) · [pack fragments from the workbench](#pack-fragments-from-the-build-workbench) ·
   [verifying a pack](#verifying-a-pack)

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
    ├── catalog.json             the list of sample entities (kind, id, title, subtitle)
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
└── samples/                     catalog.json and trade.jsonl (765 trades of the three source systems)
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
| samples | [catalog.json](examples/pack/helpdesk/samples/catalog.json) and `samples/ticket.jsonl`, `agent.jsonl`, `client.jsonl` |
| guide and starters | [helpdesk.md](examples/pack/helpdesk/guides/helpdesk.md), [help.yaml](examples/pack/helpdesk/config/help.yaml), [workspaces.yaml](examples/pack/helpdesk/config/workspaces.yaml) |

It is not in `config/packs/`, so no server loads it by accident. To follow along, build it yourself in `config/packs/helpdesk/`
(steps 1 to 15), or copy the finished folder there.

**A scratch server to try things on.** Every step ends with something you can see. Use a scratch server and console on
ports of their own, so that nothing of yours is touched: the seven shipped packs plus the one you are writing
(`DRISHTI_PACKS` is explained in [PACKS.md](PACKS.md#turning-packs-on); the start-up recipe, the token and the run configurations are in
[GETTING_STARTED.md](GETTING_STARTED.md)). Keep the demo source on (`DRISHTI_DEMO_ENABLED=true`, the default): it serves the pack's
`samples/`. Security is off unless you turn it on, so the `curl` lines below need no token; on a server with security on add
`-H "Authorization: Bearer $DRISHTI_TOKEN"`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
DRISHTI_PORT=18996 DRISHTI_PACKS=core,market-data,trading,counterparty-risk,market-risk,world-economy,news-and-markets,helpdesk \
  java -jar drishti-server/target/drishti-server-2.6.7-exec.jar
DRISHTI_BACKEND_URL=http://127.0.0.1:18996 DRISHTI_CONSOLE_PORT=17996 drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

The commands below use `B=http://localhost:18996/api/v1` for the server's API. **Packs follow the folder while the server runs:**
a change to `pack.yaml`, `config/` or `samples/` is picked up within a few seconds (the packs folder is watched, `drishti.packs.poll`),
checked with the loaded packs and swapped in; a bad edit keeps the last good version running and shows on Admin → Packs. A pack folder
copied into `config/packs/` of a running server is loaded too, without naming it in `DRISHTI_PACKS` (it is remembered in
`<data>/packs/added.yaml`). No change needs a restart (see [Versioning a pack](#versioning-a-pack) for what a change needs, and
[PACKS.md](PACKS.md#live-reload-the-packs-folder-is-watched)).

## Build it, step by step

This is the pack **by hand**, to show every file. If your data is JSON Lines or has a JSON Schema, you do not need to write these files:
`drishti.py pack new` ([below](#generating-a-pack-from-json-lines)) and [tutorial 5](../tutorials/05-build-a-pack.md) turn a schema or a folder
into a pack, deploy it and roll it back (the rules are in [SCHEMA_TO_PACK.md](SCHEMA_TO_PACK.md)), and
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
store. `samples/catalog.json` lists every sample (`kind` and `id` are required; `title` and `subtitle` are what the
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
See [INFERENCE.md](../architecture/INFERENCE.md) for how inference decides.

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
[fragment](#pack-fragments-from-the-build-workbench) ([SCREEN_DESIGNER.md](SCREEN_DESIGNER.md)).

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
tests/ticket/TKT-1001.json        copies of two samples, one document a file (or .jsonl files, one a line)
tests/ticket/TKT-1002.json
tests/ticket/expect.yaml
```

```yaml
noErrors: true            # no panel in an error state on any sample (the default)
nonEmpty: [history]       # these panels must render with data on every sample
```

```bash
java -jar drishti-server/target/drishti-server-2.6.7-exec.jar sutra test config/packs/helpdesk --junit target/sutra-tests.xml
```

```text
     ticket: help coverage 8/8 (100%)
ok   config/packs/helpdesk/sutras/ticket.v1.sutra.yaml (2 samples)
```

Exit code `0`. *Help coverage* is the share of the fields the Sutra shows that `config/about.yaml` explains (8 of 8 once the
glossary of step 10b is complete; 2 of 8 with only `AGE_HOURS` and `PRIORITY`). `target/sutra-tests.xml` is JUnit XML for your CI's
report step. Now break the expectation on purpose (`nonEmpty: [history, timeline]`):

```text
ticket / TKT-1001.json: panel timeline is not in the Sutra
ticket / TKT-1002.json: panel timeline is not in the Sutra
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
line `# Generated at first start from the connector template of pack 'helpdesk'. Edit it freely: it is never overwritten.`, and never
overwrites it afterwards. Check the connector, then open `TKT TKT-2001`:

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
| 3 | `samples/catalog.json`, `samples/<kind>.jsonl` | data with no database, a live ticking age |
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
| `sample` | server | `sample: true` | Marks the pack as a demonstration: an administrator can hide sample packs from business users (`drishti.packs.samples`) | [Sample packs](PACKS.md#sample-packs) |
| `loads` | server | `loads: { … }` | Which kinds the ETL is expected to land each business day, by when, and who is told | [DATA_LOADS.md](DATA_LOADS.md#6-configuration-reference) |
| `code` | server | `code: HELP` | The pack's short code: typed alone on the command line it opens the pack's overview (`/p/<pack>`). It must not equal a mnemonic (a mnemonic wins) | [Pack codes](PACKS.md#pack-codes) |
| `version` | server | `version: 1.2.0` | **X.Y.Z**; shown in About, Admin → Health and Admin → Packs, checked by deploys and the registry | [Versioning](#versioning-a-pack) |
| `title`, `description` | both | `title: Help desk` | The pack's name and paragraph in menus, help and admin pages | below |
| `extends` (or `requires`) | server | `extends: [market-data, trading]` | Inherit everything from these packs | [Inheritance](#inheritance) |
| `kinds` | server | `kinds: [ticket, agent]` | The kinds this pack owns | below |
| `mnemonics` | server | `TKT: { kind: ticket, label: Ticket }` | The commands | below |
| `graph.id-patterns` | server | `- { pattern: "^TKT-", kind: ticket }` | Recognise a bare id's kind | below |
| `graph.fields` | server | `ASSIGNEE: { kind: agent, label: Assigned agent }` | Turn a field's value into a link | below |
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
| `samples` | `samples` | sample documents for the built-in `demo` source (`catalog.json`, `<kind>.jsonl`) |
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

The console owns three command words: `BI`, `RUPAKA` (its alias) and `FORM`. A pack may not define a mnemonic with any of those names, in any case: the pack loader
refuses it (`pack 'x' defines the mnemonic 'BI', which is a reserved command word (BI, FORM, RUPAKA): choose another`) and `drishti.py pack check` fails the same way. No shipped pack uses them.

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
the server, once approved, in front of the pack's own `form:` until the pack carries it ([Screen designer, The Form tab](SCREEN_DESIGNER.md#the-form-tab)).

### `history`: a kind over time

`TRD 1000015 30 <GO>` shows the trade over the last 30 business days it has data; `EQ EQ-CSCA 3M` shows the equity's last three months
as a chart with its high, low and change ([HISTORY_QUERIES.md](../architecture/HISTORY_QUERIES.md)). **Any kind read from a dated
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
kinds: [agreement, csa, netting-set, credit-limit, exposure-profile, cva, sa-ccr, collateral-balance, margin-call, simm]

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
    kinds: [netting-set, credit-limit, exposure-profile, cva, sa-ccr]
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
  if no file of that name exists, logs `connector file credit-store.yaml generated from the template of pack 'counterparty-risk'`, and never overwrites a file
  afterwards. The generated file starts with `# Generated at first start from the connector template of pack '<pack>'. Edit it freely: it is never overwritten.`,
  and the server notes what it generated in `<data>/connectors/generated/<name>.properties`. After that the file is the site's: it **replaces** the template
  wholesale (the settings are not merged, so a later version of your pack cannot silently change a site's file), and the site edits it in an editor or in
  Admin → Connectors, applied live to that one connector. *Reset to pack default* (or `drishti.py connector reset <name>`) writes the pack's current template
  over it, keeping the earlier text in `<data>/connectors/history/`.
- **Kinds go through `routes:`.** You know which kind lives where, so you route it; the file's `kinds:` can only narrow that. A kind read is tried at its route's
  connector first, then the default route, then any other connector that serves the kind. A connector is therefore reusable by other packs.
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
| `graph.fields.ASSIGNEE` | `drishti.graph.fields.ASSIGNEE.kind` / `.label` |
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

## Large kinds: the lake layout

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
          row-group-rows: 1000      # opening one trade decodes one row group of documents
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
  ([DATA_LOADS.md](DATA_LOADS.md)).

The measured effect, for 1,000,000 trades a day, is in [PERFORMANCE.md](../admin/PERFORMANCE.md#a-book-of-a-million-trades-a-day); every option
of `lake build`, `lake demo` and `lake maintain` is in [TOOLS.md](TOOLS.md).

## Several source systems for one kind: the trading pack

Production rarely has one system of record for a kind. The trading pack serves `trade` from three booking systems, kept apart from
the source to the screen, and is the pattern to copy:

1. **One schema.** Every system's trade has the same 47 UPPER_SNAKE top-level attributes, nested the same way (`LEGS[] → INDEX,
   CASH_FLOWS[] → RESETS[]`, `EVENTS[]`), with `SOURCE_SYSTEM` saying where it came from ([PACKS.md, Trading](PACKS.md#trading) lists them).
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

## Derived kinds: entities computed from other kinds

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

## Samples

The built-in `demo` source serves every enabled pack's `samples/` folder (when `DRISHTI_DEMO_ENABLED` is `true`, the default), so a pack
works with no database. The format:

`samples/catalog.json` lists every sample. The demo source needs only `kind` and `id`; give `title` and
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

## Modelling deep documents: legs, cashflows and fixings

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

## Vocabulary: labels, formats and inference hints

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
how the hints fit in is in [INFERENCE.md](../architecture/INFERENCE.md). Mistake to avoid: a `labels` entry is global
to the server, so `STATUS: Ticket status` would relabel `STATUS` in every other pack that is loaded. Name labels for
fields that are yours (`AGE_HOURS`), and give a Sutra's strip entry an explicit `label:` for the shared ones.

## About text and glossary

The *About this page* drawer (`?` on a view; [how a user reads it](USER_GUIDE.md#about-this-page)) always shows where the
data came from and why the layout is what it is: those come from the engine. What only **you** can say is what a
kind is and what its figures mean. That is the pack's `config/about.yaml`, found beside `formats.yaml`,
`semantics.yaml` and `help.yaml`. The file is optional: without it the drawer still works.

![The drawer on an economy, with the world-economy pack's text](img/about/04-economy.jpg)

### The file

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

### Keys and rules

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

### What the template sees: your readers' view, not the stored document

`about` text is rendered over the document **as the person asking may see it**. A field named in
`drishti.security.redact` reads `•••` in the sentence for a role without `raw`; do not try to work around it, and do not
put values in a glossary entry (entries are never evaluated). An expression that fails renders `—` and is counted
(`drishti.explain.template-errors`), never shown as an error. A rendered text is capped at `drishti.about.max-rendered`
(1,000) characters. Formats are the pack's named formats (`fmt($.X, 'compact')`, your `formats.yaml` names too).

![For a viewer the masked limit reads as the mask in the sentence (a demo: shipped setups do not mask `LIMIT`)](img/about/05-viewer-masked.jpg)

### Inheritance: `extends`

The catalogue is built from every loaded pack's file in the order `extends` defines, **most specific pack first**
(child first, parents right to left: the same order as `semantics.yaml`). A child can replace any entry by its key
(write the whole `{ term, means }` again) and cannot delete one. There is no inheritance between kinds. Put a shared
word in the lowest pack that owns the concept (`MTM` in `trading`, which `market-risk` and `counterparty-risk` reach
through `extends`), as a `vocabulary` entry, and point to it with `use:`.

How a field's entry is found ([CONTEXT_HELP.md](../architecture/CONTEXT_HELP.md)): the kind's `glossary`
in the most specific pack, then a `vocabulary` entry with the field's last name, then, for a derived kind, the entry
generated from its definition ("Sum of `NOTIONAL` over the trades of the counterparty"), then nothing: the field shows no hint. The
explain answer (`GET /api/v1/views/<kind>/<id>/explain`) lists each shown field's entry with its `origin` (`helpdesk:glossary.PRIORITY`).

### Translations

A pack can say the same things in another language: `config/about.<lang>.yaml` beside `about.yaml` (`<lang>` is a language
tag such as `fr` or `fr-CA`). It has the same shape and the same strict parse (`DRS-2040` to `DRS-2044`, reported under
`<pack>/about.fr.yaml`) and is laid over the English file **key by key**: whatever it does not translate stays English,
so a first translation can be one kind, or one sentence. For a regional tag the plain language fills in before English
(`fr-CA`, then `fr`, then `en`). The language of an answer is `?locale=` on the explain request, else the user's *Language of
help text* on the account page, else the browser's `Accept-Language`; the first of those with an overlay wins, and the
answer's `locale` says which was used. Numbers in a template use the pack's named formats and are not localised. The
shipped example is `config/packs/market-risk/config/about.fr.yaml` (the VaR page in French).

### Generated packs

The shipped packs are generated (see [How the shipped packs are generated](#how-the-shipped-packs-are-generated)).
Their `config/about.yaml` (and any `about.<lang>.yaml`) is copied from a hand-kept source in the generator folder
(`tools/packgen/banking/about/<pack>.yaml`, `tools/packgen/<generator>/about.yaml`) by `make_packs.py` / `packbuild.py`: edit
that source and regenerate, never the generated file; `--check` fails when they differ. `sutra test` (with `help: { about: true }` in
`expect.yaml` and a `tests/help-coverage.txt` ratchet in the pack) keeps every shown field explained.

### Check it

- Load the pack: a file with a problem lists it (codes `DRS-2040` to `DRS-2044`, [RACHANA_REFERENCE.md](RACHANA_REFERENCE.md)).
- Open an entity and press `?`: the first section is your sentence, filled in. Try it as a user whose role is not
  `raw`: masked fields read `•••`.
- `sutra lint config/packs/<pack>` warns (`DRS-2046`, `DRS-2047`) about a `panels.<id>` that matches no panel and about each field a Sutra
  shows that has no glossary entry (`ticket.v1.sutra.yaml:15 warning DRS-2047 field 'ASSIGNEE' is shown but has no glossary entry
  (kinds.ticket.glossary in config/about.yaml, or a vocabulary entry)`); `sutra test` reports *help coverage n/m* per Sutra;
  `drishti.py pack about-check` lists the gaps per Sutra and exits `1` while any remain; `pack check --strict` fails on them.
  `expect.yaml` takes `help: { coverage: 0.9, about: true }` (the share of shown fields with an entry, and that `about` renders without an error).
- The Build workbench has an **About** tab to write and preview this text over your samples: the card of the previewed entity, the problems with their lines and the lint warnings, kept with the design and exported as `config/about.yaml` in the pack fragment ([SCREEN_DESIGNER.md](SCREEN_DESIGNER.md#the-about-tab)). It is a draft until you put the file in the pack: approving the design's Sutra does not make the About text live.

## Guides and help

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
things** section like the one in [PACKS.md](PACKS.md#trading), so readers know the commands work on their own data too. Links to other help documents with a relative
path (`[Sutra guide](SUTRA_DEVELOPER_GUIDE.md)`) open inside the help centre.

## Roles and field masks

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
| `calc` | may use Calc, Python in the browser on what the role opens ([PYTHON_CALC.md](PYTHON_CALC.md#9-roles-who-may-use-calc)) |

The core always has `viewer`, `author`, `approver` and `admin`. Roles are given to users by an administrator; see
[USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md). Security is off by default for local development; roles take effect when
it is on.

A pack's roles appear in *Admin → Roles* marked **built-in**, read-only: change them in the pack (or, for a
generated pack, in its generator). An administrator can also define new roles there without touching any pack;
the dialog's *Add every kind of a pack* buttons fill in a pack's kinds in one click
([USER_GUIDE.md](USER_GUIDE.md#admin--roles-what-a-role-may-do)).

### Field masks: what a role without `raw` sees

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

## Calc: Python snippets

Calc is a Python panel on any view (`Alt+C`): code that runs in the user's browser, reads the screen and Drishti with
the user's own rights, and draws tables and charts ([PYTHON_CALC.md](PYTHON_CALC.md)). A pack decides whether its
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
`config/packs/market-risk/python/var_es.py`:

```python
# title: VaR and expected shortfall from the scenario P&L
# description: Historical-simulation VaR (99%) and ES (97.5%) recomputed with numpy from the scenario P&Ls, set beside the engine's figures, and the P&L histogram.
# kinds: var

import numpy as np
import pandas as pd

pnl = np.asarray(view.doc["SCENARIO_PNL"], dtype=float)
worst = np.sort(pnl)
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
of `drishti.quant`. Every one, with the sample entity it runs on, is in [PYTHON_CALC.md › Snippet catalogue](PYTHON_CALC.md#18-snippet-catalogue).

## A signed pack registry: publishing and installing

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

## Loading a pack while the server runs

You do not need access to the machine to bring your pack in: an administrator presses **Load** in *Admin → Packs* for
a pack that is on disk but not named in `DRISHTI_PACKS` (or **Install** for one from [the registry](#a-signed-pack-registry-publishing-and-installing),
or deploys its archive); a pack folder copied into the packs folder is loaded by itself. The server runs the start-up check on the loaded
packs plus yours, records the pack in the overlay `<data>/packs/added.yaml`, and puts it to use **without a restart**; if the pack cannot be
used, the last good packs keep running. The steps, the API and the audit entries are in [PACKS.md](PACKS.md#loading-a-pack-while-the-server-runs).

What that means for the author: **the check is the one that refuses a bad pack at start-up** (a kind another pack owns, a
clash with an unrelated pack, a bad manifest), so a pack that loads on your scratch server loads on theirs. Test with
the packs they run: if their site runs `trading` and your pack defines `TRD` or `trade`, you will find out on the scratch server, not
in front of the administrator.

## Inheritance

`extends: [a, b]` means: this pack has everything `a` and `b` have — their kinds (to open), mnemonics, id patterns
and link fields, badges, roles, connectors and routes, Sutras and blocks, labels and formats, About vocabulary, samples, alert
suggestions, workspaces and help — and turning this pack on turns them on too.

When two **related** packs define the same thing differently, **the more specific definition wins**, for every user:

1. a child wins over its parents;
2. among parents, **the rightmost wins**: with `extends: [market-data, trading]`, `trading` beats `market-data`;
3. a pack reached through several parents counts once, below all of them.

This order is C3 linearisation, the rule Python uses to order classes (ADR-015).

### Worked example: the order for `counterparty-risk`

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

### Worked example: overriding a parent

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

### Worked example: a child of two parents (the rightmost wins)

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

### What can be redefined

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

## Testing a pack

Four layers, from the cheapest. The first three need no server.

| Layer | Command | What it proves |
|---|---|---|
| Sutra lint | `sutra lint config/packs/<pack>` | every Sutra and block parses and its keys, expressions and panel options are valid (`DRS-…` codes with the line); help warnings |
| Sutra tests | `sutra test config/packs/<pack>` | each Sutra renders against the samples in `tests/<sutra>/` with no panel in error, the panels `expect.yaml` names are filled, and the help coverage |
| Both, for CI | `drishti.py pack check config/packs/<pack> [--strict] [--junit DIR]` | lint and test in one command, exit `0` / `1` / `2` |
| The pack's tests in the build | `./mvnw verify` | the manifest loads, every `config/packs/*/sutras/` validates, every Sutra survives imperfect data, `sutra test` runs over every pack that has tests, every shipped example loads |
| A scratch server | the [checklist](#verifying-a-pack) | links resolve, connectors are up, commands open, the guide renders |

`sutra lint` and `sutra test` are commands of the server's exec jar (`java -jar drishti-server/target/drishti-server-2.6.7-exec.jar sutra …`);
`pack check` finds the jar itself (`--jar`, `DRISHTI_JAR`). Both read the overlay of the checkout they run in (`<data>/packs/added.yaml`), so
run them where that overlay names only packs that exist (or set `DRISHTI_DATA_DIR` to an empty folder).

### `tests/` and `expect.yaml`

```text
config/packs/<pack>/tests/<sutra>/        one folder per Sutra, named by the Sutra's name (the `sutra:` line)
    TKT-1001.json                  plain entity documents (the kind comes from the Sutra's match.kind); .jsonl files hold one document per line
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

### In CI

```sh
drishti.py pack check config/packs/helpdesk --strict --junit build/reports          # lint + test, help warnings fail, JUnit XML
java -jar drishti-server-exec.jar sutra preview config/packs/helpdesk --out target/snapshots   # HTML snapshots to attach as build artifacts
```

Publish the JUnit files with your CI's report step. A Sutra whose panels read other entities with `source:` needs
those entities' packs enabled for the run: set `DRISHTI_PACKS=…` in the job, as for the server. In this repository
`SutraCliTest` runs `sutra test` over every shipped pack that has a `tests/` folder, so a Sutra change that breaks a
pack's own tests fails the Maven build. The Build workbench writes the same layout when you export a
[fragment](#pack-fragments-from-the-build-workbench), so an exported fragment passes `sutra test` unchanged.

### The checks that run on every build

| Check | Where | What it proves | Picks up a new pack by itself? |
|---|---|---|---|
| `PackLoaderTest` | `drishti-packs` | the loader: manifests, inheritance order, overrides, refusals (clashes, cycles, missing packs) | — (tests the rules, with packs it writes itself) |
| `AllShippedPacksTest` | `drishti-packs` | the seven shipped packs load together, any two of them load without a role or mnemonic declared differently, and every retired example pack still loads on top of them | yes: every folder of `config/packs` and `examples/packs` |
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

## How the shipped packs are generated

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
UPPER_SNAKE (`COUNTERPARTY_ID`, `NET_MTM`, `EE_PEAK`, `ZERO_RATE`, `GDP_USD_BN`), as production systems write them. The
banking and world-economy generators build their documents with short internal names and turn them into the written
names at one boundary, `tools/packgen/common/upper.py` (`name`, `record` for documents and their `_meta.walk`, `kind`
for a taxonomy kind's Sutra paths, columns and links); a new field in a generator is written in UPPER_SNAKE without more
work. The trades generator writes the common schema directly. The retired example packs keep their own names
(`make_data.build(upper=False)`, `taxonomy.graph_fields(raw=True)`). The connectors that build their own documents (news, yfinance,
the public feeds) write UPPER_SNAKE too (`com.ash.drishti.common.UpperSnake`).

### Never edit a generated file by hand

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

### Changing a generated pack, step by step

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

### Changing a pack you do not own

To change a shipped pack for your site without touching its files at all, use one of these instead:

| You want | Do this |
|---|---|
| A different layout for one kind | Put a Sutra with a higher `version` in the site Sutra folder (`./sutras`, `DRISHTI_SUTRAS`), or design it in the Build workbench ([SCREEN_DESIGNER.md](SCREEN_DESIGNER.md)) |
| Other mnemonics, labels, columns, connectors or routes | A small pack of your own that `extends` the shipped one ([Inheritance](#inheritance)) |
| A connector pointed elsewhere, or switched off | Its connector file, `config/connectors/<name>.yaml` (edit it, or use Admin → Connectors or `drishti.py connector apply`): a file replaces the pack's template and is applied without a restart ([CONNECTOR_FILES.md](../connectors/CONNECTOR_FILES.md)) |
| A pack hidden from everyone | *Admin → Packs* → **Switch off** ([PACKS.md](PACKS.md#switching-packs-off-and-on-admin--packs)) |

The shipped packs' lakes are built from the committed test data with one command ([PACKS.md](PACKS.md#the-demo-lakes-where-the-seven-packs-read-their-data)):

```bash
drishti-console/.venv/bin/python tools/drishti.py lake demo
```

### Writing your own generator

A pack with many kinds and many consistent documents is better **generated**: you describe kinds and documents in a
short Python script, and the common pack builder checks them and writes the manifest, Sutras, samples, guide, help
card and (optionally) a Delta Lake. The world-economy pack and the retired example packs are made this way
(`tools/packgen/common/packbuild.py`). This walk-through generates a small library pack with two kinds (book titles
and authors); the script below was run as written (into a scratch folder, `DRISHTI_PACKS_OUT=…`) and printed the lines shown. For a
handful of kinds, writing the pack by hand ([above](#build-it-step-by-step)) is simpler, and for existing data `pack new`
([below](#generating-a-pack-from-json-lines)) is quicker.

#### What you need

- the repository checked out, and Python 3 with `pyyaml` (the console's virtual environment has it);
- the server and console running (see [GETTING_STARTED.md](GETTING_STARTED.md)), so you can open the result.

#### Generator step 1. Create the generator script

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

#### Generator step 2. Describe the kinds

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

#### Generator step 3. Write the documents

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

#### Generator step 4. Describe the pack and call the builder

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

#### Generator step 5. Run it

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

#### Generator step 6. What you get

```text
config/packs/library/
  pack.yaml                            kinds, mnemonics, id patterns, link fields, badges, roles, connector, routes, examples
  sutras/library/book-title.v1.sutra.yaml   one YAML Sutra per kind (rachana: 1, with a description)
  sutras/library/author.v1.sutra.yaml
  samples/book-title.jsonl             the documents, one a line, for the built-in demo source
  samples/author.jsonl
  samples/catalog.json                 the list the demo source and suggestions read
  guides/library.md                    an overview guide: kinds, fields, links and "Try it" commands
  config/help.yaml                     the guide's card under Help → Domain packs
```

The `pack.yaml` it wrote names the link field `AUTHOR`, the badge `$.ON_LOAN + ' on loan'` and one connector template,
`library-store` (`plugin: delta`, `domain: library`, `root: ${DRISHTI_DELTA_ROOT:${drishti.data.dir:./data}/delta}`), with
`book-title` and `author` routed to it. Never edit these files by hand: change the script and run it again (each file says so at the top).

#### Generator step 7. Open it

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

#### Generator step 8. Make it part of the build

- Commit `tools/packgen/library/make.py` and `config/packs/library/`.
- Add `library` to the default pack list (`PackPaths.DEFAULT_ENABLED`) if it should ship, so `DomainPacksTest` checks that every example
  command opens with no empty panel and no missing link, and `AllShippedPacksTest` that it loads with the others.
- `tools/drill.sh` runs every generator with `--check`, so a pack that drifts from its script fails the build.

## Generating a pack from JSON Lines

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
  samples/                  catalog.json, trade.jsonl, counterparty.jsonl (--catalog-samples, 25 a kind by default)
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

## Versioning a pack

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
[PACKS.md](PACKS.md#upgrading-and-removing-a-pack). **Before you rename a kind, a mnemonic, an attribute or a link field**, remember
that saved monitors, workspaces, alert rules and BI items refer to kinds, fields and ids: a rename leaves them pointing at nothing, so
add the new name and keep the old one for a release where you can.

## Pack fragments from the Build workbench

You do not have to write a Sutra by hand. Design it in the Build workbench (from JSON samples, a JSON Schema, or an
existing Sutra: [SCREEN_DESIGNER.md](SCREEN_DESIGNER.md)), and **ship** it as a pack fragment: *Ship → Export as a pack
fragment* downloads a zip you can put in `config/packs/`. The workbench section that describes the export, the import and the
file layout is [SCREEN_DESIGNER.md §24](SCREEN_DESIGNER.md#24-pack-fragments-export-and-import); this is what it means for
a pack author, from an export of the help-desk ticket Sutra (the design id is in the address bar):

![The Ship menu of the workbench: export as a pack fragment, import a pack, read-only link](img/packs/06-workbench-export.jpg)

```text
ticket-view/pack.yaml
ticket-view/sutras/studio/ticket.v1.sutra.yaml
ticket-view/tests/ticket/TKT-1001.json
ticket-view/tests/ticket/TKT-1002.json
ticket-view/tests/ticket/expect.yaml
ticket-view/samples/ticket.jsonl
ticket-view/samples/catalog.json
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

## Verifying a pack

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

### Version rule and the wizard

`version:` in `pack.yaml` is **X.Y.Z** (optionally `-pre-release` and `+build`, such as `2.0.0-rc.1`). `pack bundle`, `pack make --version`, Build → New pack and the server's
deploy check refuse anything else with that sentence. Build → New pack keeps your work: each step is a history entry (the browser's Back moves between steps), a reload
restores the sources and choices from the tab's storage, and leaving with unsaved work asks first. For your own JSON Lines files the default source is the **File
connector** (its generated template tells you to set `root`).
