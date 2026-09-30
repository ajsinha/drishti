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
# The twelve panel kinds

Every panel in every view is one of twelve kinds. A Sutra names the kind and binds it to data;
inference picks one when there is no Sutra. The header of each panel shows its code (`CRV`, `REFS`,
…), its key (`F2`), and an **inferred** tag when inference built or completed it. Hover over the tag to
see the rule that chose it.

## kv

**Label/value fields**, such as terms or margin. Use `rows: $.object` to show every field of an
object, or list `columns` to choose and format them. The main column shows four across; the right
column shows two.

```yaml
# kv over an object
- { id: contract, kind: kv, title: Contract, key: F2, rows: $.contractTerms }
```

## table

**Rows and columns**, such as cashflows or member trades. Columns marked `total: true` add up into
the total row; `limit` with `moreLabel` shows "N more". Columns with `link: true` open other entities.

## tabs

**One tab per element**, such as swap legs. With `layout: columns`, the elements sit side by side
instead (FX near and far legs).

## line

**A curve.** With `source: link(...)`, the points come from a linked entity (the SOFR curve), and
`mark` highlights one point (the trade's maturity).

## area

**Banded series against a limit**, such as the expected exposure and PFE 95 against a credit limit.

## hbar

**Horizontal bars**, such as DV01 by tenor or risk by factor. Negative values use the negative colour.

## ladder

**A dated table with a highlighted row**, such as daily settlements with today's intraday row.

## links

**Linked entities.** Every reference in the document becomes a door, with a short badge read from
the target (`EE 4.1m`, `threshold 0`, `live`). *pending* means the target did not arrive in time.
*no access* means your role cannot open it.

## status

**Operational fields** coloured by meaning, such as a confirmation's status.

## provenance

**How this view was built**: the layout, the data fingerprint, and the source with its generation.

## markdown

**Static notes.**

## gauge

**Utilisation against a limit.**

!!! tip "See it in Studio"
    Open [Sutra Studio](sutra-studio.md), choose `irs-vanilla v3` and change a panel's `kind`. The preview
    updates on Ctrl+Enter.
