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
# Rachana reference

**Rachana** (रचना, *composition*) is Drishti's declarative screen grammar. Because of it, no product
ever gets its own coded screen. A **Sutra** (सूत्र, *thread*) is one layout written in Rachana: a
versioned YAML file that describes how to lay out one family of entities. The mockups' *How this
view was built* reads `Sutra irs-vanilla v3 + inference`, meaning the Sutra `irs-vanilla`, version
3, completed by inference.

In short, a Sutra is a versioned YAML file that describes how to lay out one family of entities. It holds
no code and no pixels. Files live under `sutras/<domain>/<name>.v<N>.yaml`, are loaded by
`SutraRegistry` from `drishti.rachana.dirs`, and reload as soon as they are saved. If an edit is
invalid, the last good version is kept and the problems are reported. Editors can validate against
`drishti-rachana/src/main/resources/sutra.schema.json`, but the authoritative checks are the parser's,
which report every problem with its line and column.

## Top level

| Key | Required | Meaning |
|---|---|---|
| `sutra` | yes | Name: lower-case kebab, 2–64 characters (`irs-vanilla`). |
| `version` | yes | A positive integer. `name@version` is unique across all directories (`DRS-2028`). Old versions stay loadable, so a saved view reproduces. |
| `description` | | Free text. |
| `domain` | | Grouping; defaults to the parent folder (`rates`). |
| `match` | yes | `{kind, where?, priority?}`. The Sutra applies to entities of `kind` for which the Rachana-EL predicate `where` is true. The highest `priority` wins. |
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

`fmt` names a format from the bundled `formats.yaml` (in `drishti-rachana`); a site can override or add formats with `drishti.rachana.formats-file`: `amount0`, `amount2`, `signed0`,
`signed2`, `pct0`, `pct4`, `rate5`, `pips1`, `price2`, `df4`, `date`, `compact` (4.1m).

`tone` colours through theme tokens only, and always together with a sign or glyph:
- `sign`: positive values in the link colour, negative in the negative colour;
- `pos` and `neg`: fixed colours;
- `status`, `link`, `accent`: named token colours.

## Expressions (Rachana-EL)

Bindings are Rachana-EL expressions. It is closed and side-effect free:

| Element | Syntax |
|---|---|
| Document and row references | `$` is the document; `@` is the current row or element; `#index` is its position. |
| Paths | `$.legs[0].rate`, `@.amount`, `$.legs[-1]` |
| Filters | `$.legs[?@.payer]` |
| Operators | `+ - * / %`, `== != < <= > >=`, `&& || !`, `a ? b : c`; `+` also concatenates text. |
| Literals | `'text'`, numbers, `true`, `false`, `null` |
| Functions | `link(id, kind?, label?)`, `size(x)`, `sum(rows, 'field')`, `fmt(x, 'format')`, `coalesce(a, b)`, `first(x)`, `last(x)`, `abs`, `min`, `max`, `upper`, `lower` |

### Grammar

```ebnf
expr     = or [ "?" expr ":" expr ] ;
or       = and { "||" and } ;
and      = equality { "&&" equality } ;
equality = compare { ("==" | "!=") compare } ;
compare  = sum { ("<" | "<=" | ">" | ">=") sum } ;
sum      = product { ("+" | "-") product } ;
product  = unary { ("*" | "/" | "%") unary } ;
unary    = ("!" | "-") unary | postfix ;
postfix  = primary { "." ident | "[" expr "]" | "[?" expr "]" } ;
primary  = number | string | "true" | "false" | "null" | "$" | "@" | "#index"
         | ident "(" [ expr { "," expr } ] ")" | ident | "(" expr ")" ;
```

### Evaluation rules

- **Missing never throws.** A missing path evaluates to null. Null is falsy and formats as empty text.
- **Bare identifiers.** A bare identifier (`amount`) is a field of the current row when there is one, otherwise of the document.
- **`+` and text.** `+` adds two numbers; if either side is not a number, it concatenates text.
- **Division.** Dividing by zero gives null.
- **Whole numbers.** A whole-number result prints without decimals (`1 + 1` → `2`).
- **Equality.** `==` compares numbers numerically and everything else as text.
- **`link(id, kind?, label?)`** returns a link value, which the engine resolves into a navigable entity.
- **Compiled once.** Expressions are compiled once, cached by source text (`drishti.rachana.expression-cache-size`), and shared across threads.
- **Checked at load.** Every expression in a Sutra is compiled when the file loads, so a typo is reported against the file (`DRS-2101`), not at view time.
- **Dependency paths.** Each compiled expression reports the document paths it reads. Live updates (Wave 9) use this to re-evaluate only the parts of a view that a change affects.

### Choosing a Sutra

`SutraMatcher` takes the Sutras for the entity's kind, highest `priority` first, and picks the first
one whose `where` holds for the document. If none matches, the view is built by inference alone.
There is no separate classifier file: `match` is the classifier.

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
| DRS-2101 | expression or template does not compile |

## The reference Sutras

| File | Entity | Mockup |
|---|---|---|
| `sutras/rates/irs-vanilla.v3.yaml` | `TRD IRS-48213` | drishti-irs.png |
| `sutras/fx/fx-swap.v2.yaml` | `TRD FXS-20931` | drishti-fx-swap.png |
| `sutras/commodities/listed-future.v1.yaml` | `TRD CFT-77120` | drishti-commodity-future.png |
| `sutras/credit/netting-set.v1.yaml` | `NSET NS-NORTH-01` | drishti-netting-set.png |
