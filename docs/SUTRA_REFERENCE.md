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
# Sutra reference

A **Sutra** is a versioned YAML file that describes how to lay out one family of entities. It holds
no code and no pixels. Files live under `sutras/<domain>/<name>.v<N>.yaml`, are loaded by
`SutraRegistry` from `drishti.sutra.dirs`, and reload as soon as they are saved. If an edit is
invalid, the last good version is kept and the problems are reported. Editors can validate against
`drishti-sutra/src/main/resources/sutra.schema.json`, but the authoritative checks are the parser's,
which report every problem with its line and column.

## Top level

| Key | Required | Meaning |
|---|---|---|
| `sutra` | yes | Name: lower-case kebab, 2–64 characters (`irs-vanilla`). |
| `version` | yes | A positive integer. `name@version` is unique across all directories (`DRS-2028`). Old versions stay loadable, so a saved view reproduces. |
| `description` | | Free text. |
| `domain` | | Grouping; defaults to the parent folder (`rates`). |
| `match` | yes | `{kind, where?, priority?}`. The Sutra applies to entities of `kind` for which the Sutra-EL predicate `where` is true. The highest `priority` wins. |
| `title` | | `{pill, id, with}`: the title line `[pill] ID with <counterparty>`. `id` and `with` are expressions. |
| `strip` | | Up to **8** header figures: `{label, bind, fmt?, tone?, emphasis?}`. |
| `panels` | | The panels, in order. See below. |
| `keys` | | Function key → action: a panel id, `link(expr, kind)`, `impact` or `raw`. |

## Panels

Common keys: `id` (unique), `kind`, `title` (may embed `${expr}`), `key` (`F1`–`F12`, unique across
panels and `keys`), `code` (short tag at the header's right, e.g. `CRV`), `area` (`main` | `right`),
`infer` (let inference fill what is not stated), `columns` (list of
`{label, bind, fmt?, tone?, total?, link?}`), and `body` (tabs only).

| Kind | Required | Optional | Renders |
|---|---|---|---|
| `kv` | — | `rows`, `columns`, `fields` | Label/value grid. With `rows: $.obj` and no columns, every field of the object is shown. |
| `table` | `rows` | `totalLabel`, `limit`, `moreLabel`, `link` | A table. Columns with `total: true` are summed into the total row. `limit` with `moreLabel` shows "N more". |
| `tabs` | `each` | `tabTitle`, `layout` (`tabs` \| `columns`) | One `body` panel per element of `each`, as tabs or side by side (FX near/far legs). |
| `line` | — | `rows`, `source`, `x`, `y`, `mark`, `footer`, `unit` | A curve. `source: link(...)` reads the points from a linked entity; `mark` highlights one x. |
| `area` | `rows` | `x`, `series`, `limit`, `limitLabel`, `unit` | Stacked or banded areas (exposure profile) with a dashed limit line. |
| `hbar` | `rows` | `label`, `value`, `fmt`, `tone` | Horizontal bars (DV01 by tenor, risk). |
| `ladder` | `rows` | `totalLabel`, `highlight` | A dated table with a highlighted row (the intraday settlement). |
| `links` | — | — | Linked entities, resolved from the reference catalogue. |
| `status` | — | `fields` | Operational status fields (confirmation, settlement). |
| `provenance` | — | — | *How this view was built*: Sutra and version, fingerprint, source and generation. |
| `markdown` | `text` | — | Static notes. |
| `gauge` | `value` | `max`, `label`, `fmt` | Utilisation against a limit. |

## Formats and tones

`fmt` names a format from `config/formats.yaml` (Wave 5): `amount0`, `amount2`, `signed0`,
`signed2`, `pct0`, `pct4`, `rate5`, `pips1`, `price2`, `df4`, `date`, `compact` (4.1m).

`tone` colours through theme tokens only, and always together with a sign or glyph:
- `sign`: positive values in the link colour, negative in the negative colour;
- `pos` and `neg`: fixed colours;
- `status`, `link`, `accent`: named token colours.

## Expressions (Sutra-EL)

Bindings are Sutra-EL expressions. It is closed and side-effect free:

| Element | Syntax |
|---|---|
| Document and row references | `$` is the document; `@` is the current row or element; `#index` is its position. |
| Paths | `$.legs[0].rate`, `@.amount`, `$.legs[-1]` |
| Filters | `$.legs[?@.payer]` |
| Operators | `+ - * / %`, `== != < <= > >=`, `&& || !`, `a ? b : c`; `+` also concatenates text. |
| Literals | `'text'`, numbers, `true`, `false`, `null` |
| Functions | `link(id, kind?, label?)`, `size(x)`, `sum(rows, 'field')`, `fmt(x, 'format')`, `coalesce(a, b)`, `first(x)`, `last(x)`, `abs`, `min`, `max`, `upper`, `lower` |

The full grammar (EBNF) and evaluation rules land with the compiler in Wave 5.

## Problem codes

| Code | Meaning |
|---|---|
| DRS-2001 | YAML syntax error |
| DRS-2010 | missing required key |
| DRS-2011 | unknown key |
| DRS-2012 | wrong type |
| DRS-2020 | bad name or version |
| DRS-2021 | unknown panel kind |
| DRS-2022 | a required option is missing for the kind |
| DRS-2023 | option not valid for the kind |
| DRS-2024 | duplicate panel id |
| DRS-2025 | duplicate or invalid function key |
| DRS-2026 | strip longer than 8 |
| DRS-2027 | bad area |
| DRS-2028 | `name@version` defined twice |

## The reference Sutras

| File | Entity | Mockup |
|---|---|---|
| `sutras/rates/irs-vanilla.v3.yaml` | `TRD IRS-48213` | drishti-irs.png |
| `sutras/fx/fx-swap.v2.yaml` | `TRD FXS-20931` | drishti-fx-swap.png |
| `sutras/commodities/listed-future.v1.yaml` | `TRD CFT-77120` | drishti-commodity-future.png |
| `sutras/credit/netting-set.v1.yaml` | `NSET NS-NORTH-01` | drishti-netting-set.png |
