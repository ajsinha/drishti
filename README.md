<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

<p align="center"><img src="docs/requirements/drishti-logo.png" alt="Drishti" width="560"></p>

# Drishti

**Any data. Any domain. One grammar.**

Drishti (दृष्टि, "sight") is a live, keyboard-driven terminal for any kind of data. Type an identifier and it
renders the entity behind it, a swap, a counterparty, a liquidity ladder, a gene, as a dense, linked, ticking view,
from whichever system holds it. Nobody codes a screen per product:

- **Rachana** (रचना, *composition*) is a declarative screen grammar. A product family gets a **Sutra**: a short,
  versioned YAML file that says what the screen shows.
- Where there is no Sutra, or a Sutra leaves gaps, an **inference engine** reads the shape of the data and lays
  out the rest.
- **Domain packs** bring an industry: its kinds, mnemonics, Sutras, vocabulary, links, roles and connectors. The core
  knows no industry.

```
TRD MX-20000001 <GO>                          →  the trade: terms, legs, cashflows, curve, DV01 ladder, links
NSET NS-SUMMIT-NY <GO>                    →  the netting set: member trades, CSA, collateral
EXP CPTY:CP-ALDERSHOT <GO>                    →  exposure computed from the paths: EE, PFE, EPE, EEPE, from the bank to a trade
TRD where QUANTITY > 200m order by QUANTITY desc   →  every trade over 200m, largest first
amended trades over 5m in BOOK-RATES-3, biggest first   →  plain words: Drishti shows the search it makes
```

**Current release: 2.9.5** (Iceberg as a full alternative to Delta, Delta still the default; VaR hierarchies of your own from the trades' columns, such as limit letters; connector files that upgrade themselves safely; VaR from your pricing systems' scenario PVs or P&L in any currency since 2.9.4) ([release notes](RELEASE_NOTES.md) · [changelog](CHANGELOG.md) ·
[what shipped when](docs/architecture/IMPLEMENTATION_PLAN.md)). **Learn it by doing:** [twenty-nine tutorials](docs/tutorials/README.md), from your first 15 minutes to BI, AI, embedding and
production, each with real screenshots; the [guides](docs/README.md) are the reference.

## What you can do

**Look things up**
- A command line with type-ahead, as on a Bloomberg terminal: mnemonics, recent entities and matches from every
  source as you type. One match opens; several give a **pick list** with each kind's key fields
  (`TRD MX-200000`, `CPTY north`, `TRD PRODUCT_TYPE=FX_QUANTO`). The index behind it keeps several business days, can sit on disk (`DRISHTI_INDEX_DIR`) and reloads by itself or from Admin.
- **Views** of any entity: a header strip, panels (tables, curves, ladders, surfaces, maps, key-value, links), function
  keys, linked entities with badges, and the raw document (F9). Every table sorts, filters, pages and walks with
  the keyboard; id columns link to their entities.
- **The Pivot tab**, where a Sutra or pack offers it: an Excel-style pivot of a table or of a search's results (drag
  fields, or use the keys, into rows, columns, values and filters), with totals, drill-down, a chart, CSV and Excel;
  a search pivots every match of the day on the server, from its columns. Saved per user; authors promote it.
- **Structured search** (`TRD where QUANTITY >= 250m and PRODUCT_GROUP = 'Rates' order by QUANTITY desc limit 20`), or
  **plain words** (`amended trades over 5m in BOOK-RATES-3, biggest first`): a deterministic parser shows the search
  it makes, part by part, before anything runs. No AI service is involved.
- **Query by form** (`FORM <pack> <GO>`, **Forms** in the **Views** menu, `/forms`): typed inputs for a kind (text, amounts, dates, multi-select
  dropdowns, pasted lists of ids) that write the search for you, which you can name, watch, schedule or send
  ([QUERY_BY_FORM.md](docs/guides/QUERY_BY_FORM.md)); kept per data version, so a form search over 35.7 million trades answers in about 0.2 s,
  and a date with no data says so and offers the newest date that has some.
- **Impact (F8)**: what depends on an entity, what it rolls into, and the amount at stake.
- **Controls and deep documents**: a screen can offer dropdowns, toggles, date ranges and sliders that choose what a
  panel shows, and a trade shows its legs, cashflows and fixings on one screen (tabs that hold panels), with Excel-style
  column filters on every table.

**Over time**
- **Business dates**: Live, or any past business day on the server's calendar, as a static snapshot. Views,
  links, search and impact all follow the date. **Known at** shows a day as it was known at an earlier time.
- **Compare** an entity between two dates, field by field, and a search between two dates, with the change in
  every number.
- **Field history**: click any number for its line over the last 10 to 250 business days, with the date and
  connector each value came from.
- **Freshness**: every view says when its source last received data; a source quiet for longer than its
  `stale-after` is flagged, on the view and in Admin → Health.

**Live**
- Views of live entities tick over server-sent events (measured p99 about 11 ms); changed values flash.
- **Monitors** (live watchlists) and **alerts** (rules the server checks on every change, with a bell and history).
- **Workspaces**: several live views on one screen, a pane following another's selection, views dragged into panes,
  dividers dragged to resize, saved and **shared** read-only with roles or people.

**Work together**
- **Share a view with a note**, read your **inbox**, and discuss a number in a thread anchored to the data; everyone sees
  the view with their own permissions ([USER_GUIDE.md](docs/guides/USER_GUIDE.md#share-a-view-with-a-note)).
- **Notes** on an entity or one of its fields, for the next reader; the author edits, every change is audited.
- **Export and share**: CSV of any table, search or comparison; the document as JSON; print or PDF; a link that
  reopens the view exactly as you see it.
- **Reports** (an entry of **Views**): a search run as you on a schedule (`business-days 18:30`) and delivered as CSV to a
  folder or an approved webhook, with your subscriptions and alerts beside it ([SCHEDULED_REPORTS.md](docs/guides/SCHEDULED_REPORTS.md)).
- **API tokens** (read-only, revocable), a standard-library **Python client** and **Excel** through Power Query
  ([CLIENTS.md](docs/guides/CLIENTS.md)).

**Business intelligence (Rūpaka)**
- The **BI** menu and the `BI <GO>` keyword (`RUPAKA` still works): a catalogue and workspaces with roles, **data models** over
  **datasets** (tables from pack kinds; relationships, measures, row rules and masks) on an embedded query engine, **several fact tables in one
  model** and Python datasets made by a Transform; heavy questions as **BI runs** (2.6.7) in a Runs tab ([BI_RUNS_GUIDE.md](docs/guides/BI_RUNS_GUIDE.md)); **reports** with a designer, a Model view, a **map** you
  drill from the world to a country and its regions, **dropdown slicers**, cross-filtering, drill-through, a relative-date slicer, **window functions** and measures that stay correct across several fact tables, **Vega-Lite** charts, expected-value tests that stop a bad publish, and a phone and a tablet layout of their own
  ([BI_GUIDE.md](docs/guides/BI_GUIDE.md), [tutorial 10](docs/tutorials/10-first-bi-report.md), [REPORTS.md](docs/guides/REPORTS.md)).
- **BI Studio** (2.4.0): the report, model and theme designers in one shell, with an Explorer of your items, open items as tabs and a dock of
  **Problems** and **Query**; a typed `format:` per visual drives a generated Format pane; **conditional formatting** (rules, colour scales,
  icons), **themes** (Corporate and Print shipped, a theme designer), an **interactions matrix**, slicer **sync groups**, **tooltip pages**,
  **small multiples** and the **matrix at every level** ([BI_GUIDE.md section 26](docs/guides/BI_GUIDE.md#26-bi-studio-authoring-as-one-product-wave-c-240)).
- **Statements and field parameters** (2.6.0): report pages laid out on paper, page-exact PDF and Excel with headers, footers and page
  numbers, one statement per recipient through a subscription, designed in BI Studio's Paper tab ([STATEMENTS.md](docs/guides/STATEMENTS.md));
  a parameter slicer that switches which field or measure a visual draws ([tutorial 20](docs/tutorials/20-statements-and-field-parameters.md)).
- **Live BI**: live tables, data that moves together (**coherent walks**), reports that follow the ticks with pause and resume,
  incremental live measures and a history of a live table, and **boards**: report pages of live tiles (with the report's own visuals beside them) in kiosk mode, built in the report designer ([BOARDS.md](docs/guides/BOARDS.md), [tutorial 12](docs/tutorials/12-live-bi-and-boards.md)).
- **Distribution**: export a report, page or visual as PDF, PNG, Excel or CSV; **subscriptions** by email; **alerts** on a number, each person on
  their own masks, with **charts in Excel**, retries and **webhook and text** channels, and **narrative summaries**; a report **embedded** in another application ([DISTRIBUTION.md](docs/guides/DISTRIBUTION.md), [tutorial 11](docs/tutorials/11-bi-that-runs-itself.md)).
- **Govern it**: versions and diffs, review with four eyes, certification, lineage past the connector, impact of a change (renames, types), bundles between environments and inside pack archives, with rollback of an import
  ([BI_GOVERNANCE.md](docs/guides/BI_GOVERNANCE.md), [tutorial 13](docs/tutorials/13-govern-bi.md)). **Python** measures, visuals and tests, on boards and in alerts, and
  **Analyse** (slice, Drishti SQL and Python on your masked extract, worked out by the servers and promoted to a visual), **data prep step by step**
  (BI → Transforms: inputs read through a pack's connector, 21 tested snippets, a preview and a profile after every step), the browser **Python
  console** (`/pyconsole`: your own Python on a masked extract, promoted to the server); Calc's `drishti_bi` with Graphic Walker ([tutorial 14](docs/tutorials/14-python-and-analyse.md)). **AI in BI** (report from words, ask the data, explain a number, a designer copilot, a dataset helper)
  ([BI_AI.md](docs/guides/BI_AI.md), [tutorial 15](docs/tutorials/15-ai-in-drishti.md)).

**Shape it**
- **Layout mode** (`Alt+L`): drag, resize and hide a view's panels on a 12-column grid, with the mouse, a pen, a finger
  or the keyboard, and keep it as your own layout of the Sutra; an author promotes it to the Sutra through review.
- **Build -> New pack**: JSON Schema files and/or a folder of JSON Lines in, a pack out, to download or deploy
  ([SCHEMA_TO_PACK.md](docs/guides/SCHEMA_TO_PACK.md)).
- **Calc** (`Alt+C`): Python in your browser on the view you are looking at ([PYTHON_CALC.md](docs/guides/PYTHON_CALC.md)).
- **The Build workbench** (`/build`; Sutra Studio's page is folded into it; [tutorial 6](docs/tutorials/06-design-a-screen.md)): drag panels and fields onto a live screen, or a YAML editor with completion from the language's JSON Schema, live checks and a
  preview against any entity. A save is a proposal; an approver reviews the diff (four eyes).
- **Sutra Next** (2.7.0; [SUTRA_NEXT.md](docs/architecture/SUTRA_NEXT.md)): every screen built from the Drishti UI kit's
  fragments (`config/ui-kit/`, `name@N`, each with a `doc:` docstring) and the packs' own (`trading/deal-ticket@1`); panels that
  talk (`emits`, `@panel.event`), read other packs (`source:`) and open children on a click; `refresh:` with freshness; a `layout:`
  grid; `form:` with drafts. The **Sutra designer** (`ui.designer`, off by default): **Customize** a live page, **Describe** a screen
  in one line, **Outline** and **Grid** ([tutorial 22](docs/tutorials/22-build-a-screen-with-the-designer.md)).
- **Domain packs**, enabled per server and assigned per user. Eight ship; seven are on by default: `core`, `market-data`, `trading`
  (125 products), `counterparty-risk`, `market-risk`, `world-economy` (real public data; the IMF figures are for internal demonstrations only)
  and the sample `news-and-markets`; `us-equities` (SEC EDGAR, GLEIF and Wikidata public data; prices internal-only) is opt-in. The earlier `finance`, `logistics`, `genomics`, `retail-banking`, `climate-risk`, `operational-risk`
  and `liquidity-risk` are retired examples in `examples/packs/` (`DRISHTI_PACKS_EXTRA_DIRS=examples/packs`).
  Packs inherit from each other, load and unload from Admin → Packs, and install from a **signed, versioned
  registry** with rollback ([PACKS.md](docs/guides/PACKS.md)).
- **Drishti Elements**: show live views, panels and published BI reports inside another web application as `<drishti-view>`,
  `<drishti-panel>`, `<drishti-report>`, `<drishti-about>` and `<drishti-search>` Web Components, without iframes (React, Vue and Angular samples) ([ELEMENTS.md](docs/guides/ELEMENTS.md), [tutorial 16](docs/tutorials/16-embed-drishti.md)).
- **An AI layer and an MCP client**, both **off by default** (`drishti.ai.enabled`, `drishti.mcp.enabled`): pluggable
  model providers (OpenAI, Anthropic and seventeen more vendor types, each with TLS and a **Test connection** button) and *Ask about this page* ([AI_PROVIDERS.md](docs/architecture/AI_PROVIDERS.md), [MCP_CLIENT.md](docs/architecture/MCP_CLIENT.md)).
- **Derived kinds**: entities computed from others, declared in a pack, such as a counterparty's trades from every
  booking system with their totals and payments ladder (`CTRD CP-ALDERSHOT`), with history wherever their members have it;
  kept on disk, so they are complete at millions of trades a day and rebuilt only when a load changes the data.

**Connect any data**
- Delta Lake (local or S3, dated, time travel), PostgreSQL and other JDBC databases, Aerospike, Apache Cassandra (and ScyllaDB, Astra), Kafka, ActiveMQ,
  RabbitMQ, Amazon S3 and compatible stores, REST services, JSON and CSV files, and public market data (NY Fed
  SOFR, ECB €STR and FX, US Treasury, FRED), and computed ones such as news headlines for a name and Yahoo Finance quotes.
  Iceberg runs without Hadoop on **drishti-iceberg**, Drishti's own Iceberg engine (with `iceberg-external` for anyone's tables), over
  **drishti-lakeio**, the shared storage layer of the Delta and Iceberg engines (local disk and S3; 2.6.0).
  Each connector is **one YAML file** in `config/connectors`, changed live in **Admin -> Connectors** with no restart;
  TLS (private CA, mutual TLS) and an outbound HTTP proxy are settings of the file
  ([CONNECTOR_FILES.md](docs/connectors/CONNECTOR_FILES.md), [TLS.md](docs/connectors/TLS.md), [the proxy](docs/connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers)).
  Database connectors share sized **connection pools** (`drishti.db.pool.*`, shown in Admin → Health).
  Connectors reconnect by themselves, with backoff, and report their health
  ([CONNECTOR_DEVELOPER_GUIDE.md](docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md)).
- Data lands from your ETL; one call tells Drishti to refresh, verify and flag late data ([DATA_LOADS.md](docs/guides/DATA_LOADS.md)).

**Many servers, one Drishti (Drishti\*net, 2.5.0)**
- **Drishti\*net** (off by default): several servers join into one cluster by gossip; each keeps its own packs, connectors and data,
  and any of them answers any request, forwarding it to the node that holds the data with the caller's masks applied on both. A search
  covers every node's data, a live view ticks from the node that holds it, a user's saved searches and layouts follow them, and the
  console is given one server, learns the others and moves to another when it stops, with no new sign-in. Signed calls under a cluster
  key that rotates with no downtime, nodes per role, peer-only packs, Admin → Net ([NET.md](docs/admin/NET.md),
  [tutorial 19](docs/tutorials/19-a-cluster-of-three.md)). In-process tests of three servers: they list each other about 50 ms after
  the third starts; 13,542 signed calls through a key rotation, none refused. A 10-minute soak on three real servers under 20 users, one
  node killed and restarted, then the console's own: the console moved in 0.27 s with nobody signed out, and 9,362 answers checked against
  a single server, 0 wrong ([NET.md section 13](docs/admin/NET.md#13-measured-the-soak-on-real-processes)).
- **Membership** by SWIM-style gossip over HTTP (no separate port): a node is named once as a seed, the others learn each other within a
  round or two, a stopped node turns suspect then dead, and a restarted one rejoins from its saved member table. **Admin → Net → Members**
  draws the cluster as a hash ring, one arc per member coloured by its state, beside the member table.
- **Real addresses only** (2.6.0): members advertise an address other machines can call, never `localhost`, `127.0.0.1` or `0.0.0.0`
  (refused in node.yaml, in seeds and in gossip); without `advertise-url` a node takes this machine's primary IPv4 address. A user and their roles
  are known on every node, and the cluster never ends with no enabled administrator (repaired across nodes, audited `last-admin-kept`).
- **`drishti-stack/`**: a three-server cluster and a console on one machine from one `stack.yaml` (`start.sh`, `status.sh`, `stop.sh`),
  each node on the machine's real address ([drishti-stack/README.md](drishti-stack/README.md)).

**Run it safely**
- Sign-in with passwords or single sign-on (OpenID Connect), roles that decide which kinds each person may open,
  redaction of sensitive fields, and an **access log** of who looked at what (Admin → Access).
- Users, roles, packs per user, notes, reports and audit in one identity database: SQLite by default, PostgreSQL
  for production.
- **One console, many servers**: list several servers and people pick one, signing in to each separately.
- Admin → Health, Prometheus metrics, a Grafana dashboard, liveness and readiness checks, Docker images and
  runbooks ([OPERATIONS.md](docs/admin/OPERATIONS.md)).
- Ten themes (Terminal, Parchment, Blue, Wall Street, Green, Crimson, Crimson dark, and the Terminal look in one hue: Green, Crimson and Blue
  Terminal; every text colour checked at 4.5:1 on every ground), one top
  bar on every page, a strict content security policy with every asset vendored (no CDN), and phones: it works on iPhone
  and Android and can be added to the home screen.

## Try it

New to Drishti? **[GETTING_STARTED.md](docs/guides/GETTING_STARTED.md)** installs and starts it from a clean machine, and
**[tutorial 1](docs/tutorials/01-first-15-minutes.md)** takes you through your first quarter of an hour; **[IDE_GUIDE.md](docs/guides/IDE_GUIDE.md)** runs the server in IntelliJ IDEA and the console in PyCharm. The short version,
from the repository root (Java 21 or newer, production runs 21; Python 3.13, [the minimum and why](docs/guides/GETTING_STARTED.md#step-1--install-the-prerequisites), [uv](https://docs.astral.sh/uv/)):

```bash
# 1. Build (Java 21 or newer, enforced; production runs 21; Maven comes with the repository)
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./mvnw -q package -DskipTests                 # or: ./mvnw -q verify  (also runs every test)

# 2. The console's Python environment (also runs tools/drishti.py)
uv venv drishti-console/.venv && uv pip install --python drishti-console/.venv/bin/python -r drishti-console/requirements.txt

# 3. The demo lakes (the seven default packs, us-equities, gleif and funds): sixty business days from the committed test data (about 15 minutes, 3.4 GB)
drishti-console/.venv/bin/python tools/drishti.py lake demo

# 4. Start the server on :18480 with the packs to load (their parents load with them)
# (-Djava.io.tmpdir: native libraries unpack in the server's own folder, not /tmp; docs/admin/CONFIGURATION.md)
mkdir -p data/tmp/server
DRISHTI_PACKS=market-risk,counterparty-risk DRISHTI_STUDIO_SAVE=true \
  java -Djava.io.tmpdir=data/tmp/server -jar drishti-server/target/drishti-server-2.9.5-exec.jar

# 5. In a second terminal: the console on http://localhost:17480
drishti-console/.venv/bin/python drishti-console/run_drishti_web.py
```

Open http://localhost:17480/t, type `TRD MX-20000001` and press Enter. Then try `TVAL TVAL-1000008` (a trade's valuation, which
ticks), a past date in the top bar, or `amended trades over 5m, biggest first`. Without `DRISHTI_PACKS` the server loads the same seven
packs; `CPTY CP-NORTHBRIDGE` then opens a counterparty with links to its agreement and its country's economy.

Sign-in is off for local development (you act as a user with every role). To turn it on and sign in as the
development admin **`drishti-dev-admin` / `drishti-dev-admin123`** (change that password; the UI warns until you
do), see [GETTING_STARTED.md, Step 8](docs/guides/GETTING_STARTED.md#step-8--sign-in-and-change-the-admin-password).

Checks: `curl -s localhost:18480/actuator/health` and `curl -s localhost:17480/healthz` answer
`{"status":"UP"…}`; `curl -s localhost:17480/readyz` answers `{"status":"UP","server":"reachable"}` once the
console reaches the server.

## Performance at scale

Measured on one developer workstation (server heap capped at 8 GB) with `tools/bench/trades.py` and `tools/bench/users.py`;
method and every figure in [SCALE_STRATEGY.md](docs/architecture/SCALE_STRATEGY.md) and [SCALE_BENCHMARK.md](docs/admin/SCALE_BENCHMARK.md).

| Data | Measure | Result |
|---|---|---|
| 35.7 million trades: ENDUR, CALYPSO, MUREX, 300K–500K a day each for 30 days (about 5 % amended each day), loaded in random date order while serving; 25,000 counterparties, 75,000 netting sets | 50 simultaneous users against the server | 179 requests/s, 0 failures, heap 3.7 GB of 8 GB |
| the same | single trade / grouped page / netting set, p50 / p95 | 0.16 / 0.73 s, 0.17 / 0.73 s, 0.18 / 0.72 s |
| the same | 50 simultaneous users through the console | 182 pages/s, 0 failures; form search p50 0.21 s (was 5.8 s before the form was kept per data version) |
| the same | a day of 400,000 ENDUR trades amended (10 %) and reloaded while 30 users read | load 2 min 54 s; 202 requests/s, 0 failures; the new version served within a minute |
| 8.2 million trades: ENDUR, CALYPSO, MUREX, 300K–500K a day each for 7 days; 10,000 counterparties, 30,000 netting sets | 50 simultaneous users against the server | 146 requests/s, 0 failures, heap 1.8 GB |
| the same | 50 simultaneous users through the console | 69 requests/s, 0 failures |
| the same | single trade, p50 / p95 | 18 / 54 ms |
| the same | a counterparty's trades (grouped page, from disk), p50 / p95 | 70 / 99 ms |
| the same, after a restart | a table date's id map / grouped pages | 3 ms (memory-mapped; 729 ms before) / served at once |
| the same | a field's history over 30 days / a diff between two dates | 0.02–0.12 s / 6–14 ms |
| 1 million trades | search, 2,017 matches | 134 ms |
| warm cache | view built on the server / over HTTP, p99 | 0.6 ms / 3.0 ms |

## How it is built

| Layer | Technology |
|---|---|
| Server | One Spring Boot 3.5 application on Java 21 or newer (production runs 21; 25 also supported) and virtual threads, REST under `/api/v1` (OpenAPI at `/api/docs`) |
| Plugins | A Spring-free SPI found by ServiceLoader; extra jars load in isolated class loaders |
| Identity | JPA over SQLite or PostgreSQL, one schema file per database, no migrations |
| Console | Python, FastAPI and Jinja2; vendored Bootstrap, Bootstrap Icons and ECharts; no inline script |
| Gates | Error Prone and Spotless in every build; ArchUnit, licence-header and file-size tests; `tools/drill.sh` |

```
drishti/
├── drishti-api/                 plugin SPI: SourcePlugin, DataNode, EntityRef, HitIndex (no Spring)
├── drishti-common/              error codes, JSON codec, business calendar, shape fingerprints
├── drishti-rachana/             the Rachana grammar: Sutra model, YAML parser, registry, Rachana-EL, formats
├── drishti-inference/           semantic hints, layout rules, packer, Sutra ⊕ inference merge
├── drishti-graph/               links between entities, badges
├── drishti-engine/              sources and routing, derived kinds, view pipeline, impact, commands, search, phrases
├── drishti-identity/            users, roles, packs per user, notes, access log, reports' store, audit (JPA)
├── drishti-packs/               domain pack loader (inheritance, installed and shipped packs)
├── drishti-diskcache/           the connectors' RocksDB disk cache
├── drishti-messaging/           shared support for message-queue connectors
├── drishti-deltalake/           a Delta Kernel engine without Hadoop (local disk, Windows included, and S3)
├── drishti-lakeio/              the shared storage layer of the lakes: local disk and S3, Parquet footers and pages
├── drishti-iceberg/             drishti-iceberg, Drishti's own Iceberg engine without Hadoop
├── drishti-lakeload/            the parallel lake loader: JSON Lines (folder or S3) into Delta or Iceberg on every core, memory bounded (2.9.6)
├── drishti-ai/                  the AI layer: model providers, the model registry, call policy, prompt masking, MCP clients (off by default)
├── drishti-net/                 Drishti*net: membership by gossip, the cluster key, capability digests (no Spring)
├── drishti-server/              the Spring Boot application: API, security, alerts, reports, pack registry
├── drishti-stack/               a ready-made local Drishti*net cluster of three servers and a console: start.sh, stop.sh, status.sh
├── drishti-testkit/ drishti-it/ test fixtures; architecture, licence-header and file-size gates
├── drishti-benchmarks/          JMH hot-path benchmarks
├── plugins/drishti-plugin-*/     demo, file, rest, jdbc, delta, aerospike, cassandra, redis, mongodb, iceberg, iceberg-external, duckdb, feeds, news, yfinance, kafka, activemq, rabbitmq, s3
├── drishti-console/                     the web console (routes/, core/, web/templates, web/static, tests/)
├── clients/python/              drishti_client.py: the standard-library Python client
├── config/packs/<name>/                the eight shipped domain packs: pack.yaml, Sutras, vocabulary, samples, guides (retired ones in examples/packs/)
├── deploy/                      docker/ (Dockerfiles, compose; ecs/ images), aws/ (Delta lake and ECS scripts), grafana/
├── tools/                       packgen/ samplegen/ lake/ (pack data and lakes), rachana/, packreg/ (signed packs),
│                                drill.sh, license_headers.py
└── docs/                        tutorials (22), guides, references, runbooks, plan, ADRs
```

## Documentation

Start with **[Install and run](docs/guides/GETTING_STARTED.md)**, then the **[tutorials](docs/tutorials/README.md)**; the **[user guide](docs/guides/USER_GUIDE.md)** is the reference. The
**[documentation map](docs/README.md)** lists every document by what you want to do.

| Read this when… | Document |
|---|---|
| you want to learn by doing | [the twenty-nine tutorials](docs/tutorials/README.md) |
| you want it installed and running | [GETTING_STARTED.md](docs/guides/GETTING_STARTED.md) |
| you run the server in IntelliJ IDEA and the console (UX) in PyCharm | [IDE_GUIDE.md](docs/guides/IDE_GUIDE.md) |
| you build, check, load and ship packs and Sutras from a terminal or a pipeline (`tools/drishti.py`) | [CLI_GUIDE.md](docs/guides/CLI_GUIDE.md) |
| your ETL has loaded a batch and Drishti should refresh, verify, check alerts, tell people and flag late data | [DATA_LOADS.md](docs/guides/DATA_LOADS.md) |
| you ship what you built to servers by copying: bundles, checksums, promotion, rollback (no API) | [OPERATIONALISING.md](docs/guides/OPERATIONALISING.md) |
| you use the console | [USER_GUIDE.md](docs/guides/USER_GUIDE.md) |
| you use or build business intelligence (Rūpaka) | [BI_GUIDE.md](docs/guides/BI_GUIDE.md), [tutorial 10](docs/tutorials/10-first-bi-report.md), [DATASETS.md](docs/guides/DATASETS.md), [REPORTS.md](docs/guides/REPORTS.md), [PYTHON_BI.md](docs/guides/PYTHON_BI.md), [ANALYSE.md](docs/guides/ANALYSE.md), [BI_AI.md](docs/guides/BI_AI.md), [BI_GOVERNANCE.md](docs/guides/BI_GOVERNANCE.md), [BOARDS.md](docs/guides/BOARDS.md) |
| you run on Windows, or read from a script or Excel | [Install and run: Windows](docs/guides/GETTING_STARTED.md#drishti-on-windows), [CLIENTS.md](docs/guides/CLIENTS.md) |
| you use or refresh the real public data (world-economy) | [REAL_DATA.md](docs/guides/REAL_DATA.md) |
| you define connectors as files, with TLS or a proxy | [CONNECTOR_FILES.md](docs/connectors/CONNECTOR_FILES.md), [TLS.md](docs/connectors/TLS.md), [the proxy](docs/connectors/TLS.md#outbound-http-proxy-for-connectors-ai-providers-and-mcp-servers) |
| you load, assign or install a domain pack | [PACKS.md](docs/guides/PACKS.md) |
| you build a domain pack | [PACK_DEVELOPER_GUIDE.md](docs/guides/PACK_DEVELOPER_GUIDE.md) |
| you learn to build screens in the Build workbench, step by step | [tutorial 6](docs/tutorials/06-design-a-screen.md) |
| you change a live page in plain words with the Sutra designer | [tutorial 22](docs/tutorials/22-build-a-screen-with-the-designer.md) |
| you learn to write a Sutra | [SUTRA_DEVELOPER_GUIDE.md](docs/guides/SUTRA_DEVELOPER_GUIDE.md) |
| you choose or add a panel kind | [PANELS.md](docs/guides/PANELS.md) (the kinds, and [developing one](docs/guides/PANELS.md#developing-a-panel-kind)) |
| you need an exact Sutra key, format or expression | [RACHANA_REFERENCE.md](docs/guides/RACHANA_REFERENCE.md) |
| a view is laid out by inference and you want to know why | [INFERENCE.md](docs/architecture/INFERENCE.md) |
| you connect your own data | [CONNECTORS.md](docs/connectors/CONNECTORS.md) (a section per plugin), [CONNECTOR_DEVELOPER_GUIDE.md](docs/connectors/CONNECTOR_DEVELOPER_GUIDE.md) and the per-connector documents |
| you need a setting's name, default and environment variable | [CONFIGURATION.md](docs/admin/CONFIGURATION.md) |
| you manage users, roles, single sign-on, tokens or the access log | [USER_MANAGEMENT.md](docs/admin/USER_MANAGEMENT.md) |
| you script against it, from Python, Excel or curl | [CLIENTS.md](docs/guides/CLIENTS.md), [API_GUIDE.md](docs/guides/API_GUIDE.md) |
| you run it in production | [OPERATIONS.md](docs/admin/OPERATIONS.md), [PERFORMANCE.md](docs/admin/PERFORMANCE.md), [LIVE.md](docs/architecture/LIVE.md) |
| you run several servers as one cluster (Drishti\*net) | [NET.md](docs/admin/NET.md), [tutorial 19](docs/tutorials/19-a-cluster-of-three.md), [DRISHTI_NET.md](docs/architecture/DRISHTI_NET.md) |
| you serve millions of entities a day for years | [DELTA_CONNECTOR.md](docs/connectors/DELTA_CONNECTOR.md), [POSTGRES_CONNECTOR.md](docs/connectors/POSTGRES_CONNECTOR.md), [AEROSPIKE_CONNECTOR.md](docs/connectors/AEROSPIKE_CONNECTOR.md) |
| you need demo data, small or large | [DEMO_DATA.md](docs/connectors/DEMO_DATA.md) |
| something is not working | [TROUBLESHOOTING.md](docs/guides/TROUBLESHOOTING.md), [runbooks](docs/admin/runbooks/) |
| you change the code | [DEVELOPER_GUIDE.md](docs/guides/DEVELOPER_GUIDE.md) |
| you want to understand the design and its decisions | [ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md), [ADRs](docs/architecture/adr/README.md) |
| you want to know what shipped when, and what is next | [IMPLEMENTATION_PLAN.md](docs/architecture/IMPLEMENTATION_PLAN.md), [CHANGELOG.md](CHANGELOG.md) |

## Contributing rules

- Work on `develop`. `tools/drill.sh` runs every check, pushes `develop` and fast-forwards `main`; releases are
  tagged on `main` after it.
- Every build runs Error Prone and Spotless; `./mvnw -q verify` must pass, as must the console tests
  (`drishti-console/.venv/bin/python -m pytest -q drishti-console/tests`).
- No source file over 1,500 lines (UX templates excepted), and every file carries the copyright header
  (`python3 tools/license_headers.py --fix`). Both are enforced by tests.
- Modules depend in one direction only, controllers live only in the server, and the plugin SPI is Spring-free
  (ArchUnit).
- Front-end assets are vendored; a test rejects any external URL. Product name and legal notices come from
  configuration, never from code.

## Legal

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.

This software is **proprietary and confidential**. Copying, use, modification, distribution or
disclosure without the express prior written permission of the copyright holder is prohibited.
See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
