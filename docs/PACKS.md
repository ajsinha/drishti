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

## Enabling packs

```bash
# Server and console read the same list (the console asks the server)
DRISHTI_PACKS=finance,logistics
```

Packs load in the order given. The server merges each pack's content as the **lowest-precedence**
configuration, so anything the site sets in `application.yaml` overrides a pack. If two packs
claim the same mnemonic, reference field, badge or role, the server refuses to start, and names both
packs. `GET /api/v1/packs` and the About page list the active packs.

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

A pack can also suggest **alert rules** per kind (`alerts:` with `kind`, `name`, `when`, `severity`,
`message`) and offer **starter monitors** (`console.monitors: { Name: [ {kind, id}, … ] }`).

Sample fixtures tick while someone watches them when their `_meta` says so. `"walk": {"etaDelayHours": 1}`
random-walks those fields, so a pack needs no code for live samples.

## Writing a pack

1. Copy `packs/logistics` to `packs/<yours>`, and set `pack: <yours>` in `pack.yaml`.
2. Declare mnemonics, identifier patterns and reference fields for your entity kinds.
3. Point a source at your data (see the plugin guide), or add samples.
4. Open an entity. It renders by inference straight away. Use **Sutra Studio → Start from inference**
   to turn that into a Sutra, then save it under `sutras/`.
5. Add semantic hints for your field names, a starter workspace, and a guide.
6. Run with `DRISHTI_PACKS=finance,<yours>`.

No Java or Python changes are needed. A pack is configuration and content only.

## What stays in the core

- The engine: sources, pipeline, Rachana and Rachana-EL, inference, the entity graph, live updates, identity.
- Neutral formats (amounts, signed, percent, compact, dates).
- Neutral semantic roles (amount, value, count, rate, date, label).
- The `viewer`, `author` and `admin` roles.
- The generic help: using the terminal, panel kinds, Studio, workspaces, and every reference.
