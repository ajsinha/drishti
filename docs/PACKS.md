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

Drishti's core knows no industry. It works with documents, shapes, the Rachana grammar, inference,
links and live updates. An industry arrives as a **domain pack**: one folder that contains
everything specific to that domain. Enable the packs you need; everything else stays neutral.

| Pack | What it brings |
|---|---|
| `finance` (default) | Trades (IRS, FX swap, futures, and more), netting sets, CSAs, agreements, curves, limits; the four reference Sutras; finance vocabulary (MTM, DV01, notional, pips); `trader` and `risk` roles; the mockups' live sample data; the capital-markets tutorials |
| `logistics` | Shipments, reefer containers, vessels, ports; a shipment Sutra; logistics vocabulary (weights, temperatures, delays, knots, TEU); an `ops` role; live samples; a Shipment tracker workspace |
| banking family | `banking-core`, `market-data`, `trading`, `market-risk`, `counterparty-risk` (generated from one taxonomy), plus `liquidity-risk`, `climate-risk`, `operational-risk` and `retail-banking`; see below |
| other domains | `genomics` (genes, variants, proteins, samples, trials), `politics-society` (fictional polities: elections, polls, bills), `economics` (economies, indicators, central banks, forecasts, trade) |

## Enabling packs

```bash
# Server and console read the same list (the console asks the server)
DRISHTI_PACKS=finance,logistics
```

Packs load in the order given. The server merges each pack's content as the **lowest-precedence**
configuration, so anything the site sets in `application.yaml` overrides a pack. If two packs
claim the same mnemonic, reference field, badge or role, the server refuses to start, and names both
packs. `GET /api/v1/packs` and the About page list the active packs.

## Who sees which pack

- **Installed** packs (`DRISHTI_PACKS`) are what the server runs. Each pack lists the entity `kinds` it
  owns. A kind belongs to exactly one installed pack, which is why `finance` and `risk` cannot be
  installed together.
- **Assigned** packs are those an administrator gives a user (*Admin → Users → Edit → Packs*). A user
  without an assignment gets `drishti.packs.default-for-users` (default: every installed pack).
- **Active** packs are what the user chose to see, using the pack switcher in the top bar (shown when
  more than one pack is assigned). The choice is saved to their account.
- **Enforcement is on the server.** A kind owned by a pack that is not active for the user cannot be
  opened (`DRS-5002`). Its mnemonics, suggestions, examples, starters, guides and alert suggestions
  disappear with it. Kinds that no pack owns are unaffected.

## What a pack contains

```text
# packs/<name>/
pack.yaml                  name, version, title, description, and the vocabulary below
sutras/                    Sutras (layouts) for the domain's entities
config/semantics.yaml      field-name roles for inference, tried before the core roles
config/formats.yaml        extra named formats (temp1, pips1, …)
config/workspaces.yaml     starter workspaces
config/help.yaml           help guides and the F1 context they answer
guides/*.md                the guides themselves
samples/catalog.json …     sample entities for the demo source (optional)
tools/                     anything that generates the samples
```

`pack.yaml` declares the domain's vocabulary:

```yaml
# packs/logistics/pack.yaml (abridged)
pack: logistics
version: 1.0.0
title: Logistics · ocean freight
mnemonics: { SHP: { kind: shipment, label: Shipment } }
graph:
  id-patterns: [ { pattern: "^SHP-", kind: shipment } ]
  fields:      { vessel: { kind: vessel, label: Vessel } }
  badges:      { vessel: "fmt($.speedKnots, 'knots1')" }
roles:         { ops: { kinds: [shipment, container, vessel, port] } }
console:
  examples:   [ ["SHP SHP-10042", "Shipment in transit"] ]
  workspaces: config/workspaces.yaml
  help:       config/help.yaml
```

Impact (F8) is configured under `graph.impact`:
- `follow` lists the fields used to roll dependents up;
- `measures` gives the Rachana-EL expression summed per kind;
- `formats` gives how to show it.

A pack can also suggest **alert rules** per kind (`alerts:` with `kind`, `name`, `when`, `severity`,
`message`) and offer **starter monitors** (`console.monitors: { Name: [ {kind, id}, … ] }`).

Sample fixtures tick while someone watches them when their `_meta` says so. `"walk": {"etaDelayHours": 1}`
random-walks those fields, so a pack needs no code for live samples.

### Dependencies, connectors and routes

A pack can build on others and says where its data comes from:

```yaml
# packs/counterparty-risk/pack.yaml (abridged)
requires: [trading]                 # loads trading first, and what trading requires (market-data, banking-core)
connectors:                         # one per data domain it reads; declared identically by every pack that uses it
  credit-store:     { plugin: delta, kinds: [netting-set, credit-limit, …], settings: { root: "${DRISHTI_DELTA_ROOT:./data/delta}", domain: credit } }
  collateral-store: { plugin: delta, kinds: [collateral-balance, margin-call, simm], settings: { domain: collateral } }
routes:                             # which connector answers each of the pack's kinds
  netting-set: credit-store
  margin-call: collateral-store
```

- **`requires`**: enabling a pack enables what it requires, dependencies first. A user who may see
  `market-risk` can open the kinds of the packs it requires (a VaR result links to its trades and books).
  Missing packs and cycles stop the server with a clear message.
- **Data domains and packs are many-to-many.** Delta Lake is organised by data domain (`data/delta/<domain>/<kind>/`),
  not by pack. A pack may read several domains, and one domain serves every pack that uses it: several packs may
  declare the same connector, identically. A different declaration under the same name is an error.
- **`routes`** map each of the pack's kinds to the connector that answers it.
- Site configuration (`drishti.sources.connectors.<name>`) overrides anything a pack declares, for example
  to point a domain at a database instead of the lake, or to switch it off.

### The banking packs

Five packs cover market risk and counterparty credit risk. They are generated from one taxonomy
(`tools/packgen/banking/`: `make_packs.py` writes the manifests, `make_sutras.py` the 170 Sutras).

| Pack | Requires | Kinds | Data domains |
|---|---|---|---|
| `banking-core` | — | counterparties, groups, issuers, agreements, CCPs, legal entities, books, desks, traders, calendars, CSAs, clearing accounts | `reference` (effective-dated) |
| `market-data` | banking-core | curves, vol surfaces and cubes, FX, equities, indices, dividends, credit, inflation, commodities, fixings, bonds, correlations | `market` |
| `trading` | banking-core, market-data | `trade`: 125 products in ten asset classes | `trading` |
| `market-risk` | trading | VaR, stress scenarios and results, FRTB sensitivities, P&L explain | `risk` |
| `counterparty-risk` | trading | netting sets, credit limits, exposure profiles, CVA, SA-CCR, collateral balances, margin calls, SIMM | `credit`, `collateral` |

Enable them with `DRISHTI_PACKS=market-risk,counterparty-risk` (the others come with them).

### More generated packs

Further packs describe themselves with a `PackSpec` (kinds, data domains, examples) and one data function, and
`tools/packgen/common/packbuild.py` writes the manifest, the Sutras, the samples, the guide and the lake, after
checking every id, link and Sutra path.

| Pack | Requires | Kinds | Data domain |
|---|---|---|---|
| `liquidity-risk` | trading | LCR, NSFR, maturity ladders, HQLA holdings, funding sources, liquidity stress, intraday liquidity | `liquidity` |
| `climate-risk` | trading | climate profiles, PCAF financed emissions, NGFS scenarios, climate stress, physical-risk assets, green asset ratio | `climate` |
| `operational-risk` | banking-core | loss events, RCSA, key risk indicators, issues and actions, scenarios, third parties, cyber incidents, SMA capital | `oprisk` |
| `retail-banking` | banking-core | customers, deposit accounts, mortgages, cards, personal loans, branches, collections cases, IFRS 9 portfolios | `retail` |
| `genomics` |
| `politics-society` | — | jurisdictions, parties, candidates, elections (D'Hondt and first past the post), polls, bills, regions, social indicators (fictional polities) | `civic` |
| `economics` | — | economies, macro indicators and releases, central-bank decisions, forecasts, trade flows, labour markets, fiscal positions, price baskets | `macro` | — | genes, variants, proteins, pathways, samples, sequencing runs, expression studies, clinical trials | `genomics` |

The generated packs and `finance` (the small demo behind the mockups) both define trades and counterparties,
so a site runs one family or the other.

## Writing a pack

1. Copy `packs/logistics` to `packs/<yours>`, and set `pack: <yours>` in `pack.yaml`.
2. Declare mnemonics, identifier patterns and reference fields for your entity kinds.
3. Point a source at your data (see the plugin guide), or add samples.
4. Open an entity. It renders by inference straight away. Use **Sutra Studio → Start from inference**
   to turn that into a Sutra, then save it under `sutras/`.
5. Add semantic hints for your field names, a starter workspace, and a guide.
6. Run with `DRISHTI_PACKS=finance,<yours>`.

No Java or Python changes are needed. A pack is configuration and content only.

## Realistic sample data

Samples should look like what the real system would hold, not like a test fixture. `tools/samplegen` is a small,
standard-library-only toolkit that packs use to generate them:

| Module | What it gives |
|---|---|
| `ids` | LEI (ISO 17442 check digits), ISIN (Luhn), CUSIP, UTI, UPI, BIC |
| `dates` | business-day calendars (USNY, GBLO, EUTA, JPTO and joint calendars), tenors, schedules with stubs, day counts (ACT/360, ACT/365F, 30/360, 30E/360, ACT/ACT) |
| `curves` | zero curves with log-linear discount factors, forwards and the published pillar table |
| `legs` | fixed and floating legs with every calculation period: fixing dates, year fractions, fixings or projected rates, amounts, DFs, PVs and status; repricing helpers |
| `blocks` | execution, lifecycle and audit, confirmation, clearing, regulatory reporting, settlement instructions, valuation and P&L history |

Generators keep values the mockups show and derive everything else consistently. For example, a thin swap's
fixed rate is solved so its cashflows reprice to its MTM. `tools/samplegen/test_samplegen.py` checks the
library against known values (a real ISIN and CUSIP, the mockup's cashflows) and the finance samples against
themselves. Drill runs it.

## What stays in the core

- The engine: sources, pipeline, Rachana and Rachana-EL, inference, the entity graph, live updates, identity.
- Neutral formats (amounts, signed, percent, compact, dates).
- Neutral semantic roles (amount, value, count, rate, date, label).
- The `viewer`, `author` and `admin` roles.
- The generic help: using the terminal, panel kinds, Studio, workspaces, and every reference.
