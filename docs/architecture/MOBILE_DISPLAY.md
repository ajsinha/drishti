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
# Phone display: what goes to a phone, configured

**Status: design, not built (2.9.7 candidate, product owner 2026-10-30).** No code yet. The decisions for the product owner are in
[section 11](#11-decisions-for-the-product-owner).

**The request (product owner):** "Let us see if mobile display can be optimized. Not all panels need to go to mobile; it makes the
display very dense and hard to handle. Propose a configurable design which allows must-have displays to go to mobile so things load
in the phone well. Do not code, just design, and keep it configurable. Something will need to be done in Sutra."

## Contents

1. [Today](#1-today)
2. [The idea in one paragraph](#2-the-idea-in-one-paragraph)
3. [Phone tiers: must, glance, more, desk](#3-phone-tiers-must-glance-more-desk)
4. [Where the tier is set: five layers, one rule](#4-where-the-tier-is-set-five-layers-one-rule)
5. [Sutra: the grammar](#5-sutra-the-grammar)
6. [Loading less, not hiding more](#6-loading-less-not-hiding-more)
7. [The phone screen](#7-the-phone-screen)
8. [Beyond Sutra pages](#8-beyond-sutra-pages)
9. [Lint, tests and evidence](#9-lint-tests-and-evidence)
10. [How it would be built](#10-how-it-would-be-built)
11. [Decisions for the product owner](#11-decisions-for-the-product-owner)

## 1. Today

What happens on a phone now (read from [SUTRA_NEXT.md section 13](SUTRA_NEXT.md#13-layout-placing-panels) and the console):

- **Every panel goes to the phone.** Below 640 px every panel stacks full width, in `order` or the Sutra's `layout.phone:` list. A
  netting set or a deep trade becomes a long column of a dozen boxes: key figures, then tables, charts, history, provenance, checks.
- **Every panel is loaded.** The server binds every panel of the ViewModel, the console renders all of them, and every live panel
  subscribes, whether the reader ever scrolls to it or not. On a phone over a mobile network that is the slow part.
- **What exists to build on:**
  - `layout.phone:` orders panels and areas, but cannot leave one out;
  - `when:` leaves a panel out on a condition over the document, but not over the device;
  - `collapsible: true` folds a panel, but it is still loaded;
  - BI reports have their own `layouts.phone`;
  - the console already draws tables that scroll inside themselves, sheets for details, and 44 px controls.

The gap: **a way to say which panels matter on a phone, and to not load the others until asked.**

## 2. The idea in one paragraph

Every panel gets a **phone tier**: **must** (always shown, loaded first), **glance** (shown in a compact form: a few rows, a sparkline,
a figure instead of a chart), **more** (not loaded; listed under *More on this page*, loaded when tapped) or **desk** (not on a phone at
all; *Open on desktop* says it exists). The tier is **configured, never coded**. A sensible default comes from the panel's kind, a
fragment declares its own, a Sutra overrides per panel, an administrator overrides per pack, edition or site, and a person may pin a
panel for their own phone. The console tells the server it is a phone, and the server **binds only must and glance panels**, so the
phone loads a third of the data and subscribes to a third of the streams.

## 3. Phone tiers: must, glance, more, desk

| Tier | On a phone | Loaded | Live | Typical panels |
|---|---|---|---|---|
| **must** | shown in full, at the top in its phone order | with the page | yes | the header strip, key figures, status and breaches, the one table the page is about (in its phone columns) |
| **glance** | shown in a compact form (section 5.3) with *Show all* | with the page, the compact data only | yes, the compact data | ranked bars, a history chart as a sparkline, a long table as its top 5 rows |
| **more** | a row in *More on this page* (title, one-line summary when known), tapped to load in place | on tap | after loading | contributions, cash-flow ladders, provenance, checks, secondary tables, notes |
| **desk** | not shown; *Open on desktop* lists them, with a link that opens the same page and state on a bigger screen (or emails or copies it) | never on a phone | no | designers, wide pivot grids, multi-chart dashboards, editing forms too wide for a phone |

**Rules:**
- A page always has at least one **must** panel. If configuration leaves none, the first panel in order is must (and lint warns).
- **Tablet** (640 to 1000 px) shows must, glance and more; desk panels are shown, but behind *More on this page*.
- **Desktop is unchanged.** Tiers only act below the breakpoints, and a person can always tap *Show everything* for the session.
- Masks, roles and entitlements are unchanged: a tier never shows what the viewer may not see, and a desk panel is not a way round a
  role.

## 4. Where the tier is set: five layers, one rule

The tier is resolved per panel, **the most specific layer wins**:

| Layer (most general first) | Where | Who | Example |
|---|---|---|---|
| 1. **Kind default** | built in, per panel kind | Drishti | `key-figure`, `field-list` (header) → must; `table` → glance; `chart`, `ranked-bars` → glance; `provenance`, `notes`, `json` → more; `pivot`, `designer` → desk |
| 2. **Fragment** | the fragment's `doc.phone` (`counterparty-risk/exposure-profile@2`) | pack author | an exposure profile is glance (a sparkline of EE and PFE) |
| 3. **Sutra** | `phone:` on a panel, or the screen's `phone:` block (section 5) | pack or screen author | on the netting-set page the MtM panel is must, the trades table glance, the computed checks more |
| 4. **Site, pack or edition** | `config/phone.yaml` and an edition's `phone:` block | administrator | in Drishti Risk, every VaR contribution panel is more; across the site, provenance is desk |
| 5. **The person** | *Pin to my phone* or *Not on my phone* on a panel's menu; stored per person and Sutra (the arrangement store already used by `user-can-arrange`) | the reader | a trader pins the limits panel to must |

- **Layer 4 can cap as well as set:** `max: glance` stops a heavy panel from ever being must on a phone, whatever a Sutra says. The cap
  is how a site protects phone performance; a person's pin cannot exceed it.
- **Resolution is server-side and explainable.** The ViewModel carries, per panel, the resolved tier and the layer that set it (`phone:
  { tier: glance, from: sutra }`). *About this page* shows it, so "why is this not on my phone?" has an answer.

## 5. Sutra: the grammar

### 5.1 Per panel

```yaml
panels:
  - { use: field-list@1, with: { id: header, rows: $.SUMMARY }, phone: must }
  - id: trades
    kind: table
    rows: $.TRADES
    phone: { tier: glance, rows: 5, columns: [TRADE_ID, PRODUCT, MTM], sort: -MTM }   # the compact form
  - { use: ranked-bars@1, with: { id: limits, rows: $.LIMITS, label: LIMIT, value: USED }, phone: glance }
  - { use: cashflow-ladder@1, with: { id: flows, rows: $.CASHFLOWS }, phone: more }
  - { use: data-provenance@1, phone: desk }
```

`phone:` is a word (`must`, `glance`, `more`, `desk`) or an object `{ tier, …compact options }`.

### 5.2 Per screen

```yaml
phone:
  order: [header, mtm, trades, limits]      # replaces layout.phone (kept as an alias); only must and glance panels
  default: more                             # the tier of panels this Sutra does not name (instead of the kind defaults)
  summary: "Net MTM {$.NET_MTM | fmt:usd0}, {$.TRADE_COUNT} trades"   # one line at the top of the phone page
```

### 5.3 Compact forms (glance)

| Panel kind | Compact form | Options |
|---|---|---|
| table | the top N rows, chosen columns, one sort | `rows`, `columns`, `sort` |
| chart (line, area) | a sparkline with the last value and the change | `series` (one or two) |
| bars, ranked-bars | the top N bars | `rows` |
| key-figure group | the first N figures, the rest under *Show all* | `figures` |
| tabs | the first tab only; the other tabs as *More* rows | `tab` |
| map, datamap | a list of the top items instead of the map | `rows` |

The compact form is **bound on the server** (the top N rows sorted and cut there), so the phone does not download the whole table to
show five rows. *Show all* asks for the full panel.

### 5.4 Fragments

A fragment declares its default in `doc.phone` (`phone: { tier: glance, rows: 5 }`), and lint requires it for fragments that draw a
table or a chart, so a pack's shared blocks behave well on phones without every Sutra repeating it.

## 6. Loading less, not hiding more

Hiding a panel with CSS would leave the phone just as slow. The design loads less:

1. **The console tells the server the device class.** It sends `X-Drishti-Device: phone | tablet | desk`, from the viewport width at
   page load and on rotation. The server never guesses from the user agent; an embedding page or the API can set it.
2. **The server binds by tier.** Must panels in full; glance panels in their compact form; more and desk panels **not bound**, sent
   as stubs (`id`, `title`, `tier`, and the one-line summary when it is cheap). That works as a permission-checked placeholder.
3. **Streams follow.** Live subscriptions are opened only for panels on screen. A *more* panel subscribes when it is loaded, and
   unsubscribes when the page leaves.
4. **One request per tap.** *More on this page → Cash flows* fetches that panel alone (`GET …/views/<sutra>/<id>/panels/flows`), bound
   as the viewer, with the page's state (selection, filters, date).
5. **Arrow where it pays** (2.9.7 row 0). A glance table is small and stays JSON; a *Show all* of a large table on a tablet uses Arrow.
6. **Measured.** The design's success measure is phone payload and time to first meaningful paint on the heaviest pages (netting set,
   deep trade, VaR page, counterparty). Target: under half of today's bytes and under 1.5 s to the must panels on a mid-range phone
   over 4G (to be measured before and after).

## 7. The phone screen

```
┌──────────────────────────────┐
│ ☰  NSET NS-ALDERSHOT-NY   ⋮ │  header (must): title, status, date
├──────────────────────────────┤
│ Net MTM 214.7m · 270 trades  │  phone summary line
├──────────────────────────────┤
│ ▌ CE 214.7m   EPE 37.4m      │  key figures (must)
│ ▌ PFE95 357.4m  Limit 62%    │
├──────────────────────────────┤
│ Top trades by MTM      ▸ all │  trades (glance: 5 rows, 3 columns)
│ IRS-48213   Swap    41.2m    │
│ …                            │
├──────────────────────────────┤
│ EE / PFE  ▁▂▃▅▆▇▆▅  357.4m ▸ │  exposure profile (glance: sparkline)
├──────────────────────────────┤
│ MORE ON THIS PAGE            │  more: tap to load in place
│  Computed from valuations  ▸ │
│  Checks                    ▸ │
│  Collateral                ▸ │
├──────────────────────────────┤
│ ON DESKTOP: provenance, pivot│  desk: open on desktop, copy link
└──────────────────────────────┘
```

- **Order:** the phone summary, then must panels, then glance, then *More on this page*, then *On desktop*. A screen's `phone.order`
  arranges must and glance; *more* rows keep the desktop order.
- **One hand:** 44 px targets, no sideways page scroll, a table scrolls inside itself, details open as full-width sheets with Back.
- **The person's choices:** a long press on a panel (or its ⋮ menu) offers *Pin to my phone* and *Not on my phone*; *Reset phone
  layout* returns to the configured one.

## 8. Beyond Sutra pages

The same four tiers and the same `phone:` keys, configured where each page is configured:

| Page | Configured in | Phone default |
|---|---|---|
| Edition landings (Risk, World, Market desks) | the edition's `home.phone:` | the tiles and the first card must; tables glance; maps and charts more |
| VaR page | the market-risk pack's `var.page.phone` | headline VaR and ES and limit use must; the P&L path glance (sparkline); contributions and history more; the what-if editor desk |
| Exposure page | the counterparty-risk pack's `exposure.page.phone` | CE, EPE and peak PFE must; the profile glance; netting sets more; paths desk |
| BI reports | the report's existing `layouts.phone` (unchanged), plus `phone: desk` on visuals | as the report author sets |
| Data map | the console's `ui.datamap.phone` | the list view instead of the map (already a glance form) |
| Terminal and search | built in | results as cards, top 20 (glance), *Load more* |
| Admin pages, designers, Studio | built in | desk, with a short *Open on desktop* page |

## 9. Lint, tests and evidence

- **Lint** (`DRS-2059` family): `phone:` names a tier; compact options fit the kind (no `rows` on a key figure); `phone.order` names
  only must and glance panels; a page with no must panel warns; a fragment drawing a table or chart without `doc.phone` warns (error
  under `--strict`); a site cap that a Sutra exceeds is reported, not an error (the cap wins).
- **Tests:**
  - the resolution order of the five layers, including the site cap;
  - the ViewModel binds only must and glance panels for `X-Drishti-Device: phone`;
  - a *more* panel loads on tap, bound as the viewer;
  - no subscription for an unloaded panel;
  - masks unchanged;
  - browser tests at 390 px on the heaviest pages, asserting payload bytes and that no sideways scroll appears;
  - the same pages unchanged at 1280 px.
- **Coverage report:** `sutra lint --phone` lists every Sutra with its panels' resolved tiers and the payload estimate for a phone, so
  an author sees heavy pages before users do.

## 10. How it would be built

| Step | What | Size |
|---|---|---|
| 1 | Resolution (five layers, caps) and the ViewModel's `phone` per panel; `X-Drishti-Device`; lint | M |
| 2 | Server binding by tier: stubs for more and desk, compact binding for glance (top N, columns, sparkline series) | M |
| 3 | Console: the phone page (summary, must, glance, *More on this page*, *On desktop*), loading a panel on tap, streams per loaded panel | M |
| 4 | `phone:` defaults in the shipped packs' fragments and their heaviest Sutras (netting set, counterparty, deep trade, VaR, exposure); the landings | M |
| 5 | Person pins (*Pin to my phone*, *Not on my phone*, reset) in the arrangement store | S |
| 6 | Measurement before and after; docs (SUTRA_DEVELOPER_GUIDE, USER_GUIDE "On a phone", CONFIGURATION, EDITIONS_GUIDE); screenshots at 390 px | S |

Mock-ups first: two or three phone mock-ups of one heavy page (the netting set) for the product owner to choose from before step 3.

## 11. Decisions for the product owner

1. **The four tiers** (must, glance, more, desk), or three (drop desk and treat it as more)?
2. **The kind defaults** in section 4: tables glance and charts glance, or charts more by default?
3. **Who may cap:** site and edition caps (`max:`) as proposed, or Sutra authors only?
4. **Person pins** in the first build, or later?
5. **Tablet** as proposed (desk panels behind *More on this page*), or tablet treated as desktop?
6. **Release:** in 2.9.7, or with the client-side renderer (which renders the same ViewModel elsewhere and benefits from the same tiers)?
