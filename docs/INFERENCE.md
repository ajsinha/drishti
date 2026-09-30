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
# Inference

When an entity has no Sutra, or a Sutra leaves gaps, Drishti infers the layout from the **shape**
of the document. Inference is deterministic and explainable: each inferred panel records the rule,
its score and its reason. These appear in *How this view was built* and in Sutra Studio. Inference
runs only on the cold path, because the result is cached per (Sutra version, shape fingerprint).

## Pipeline

```
document ─► rules propose candidates ─► best candidate per document path ─► packer ─► InferredLayout
                                                                                         │
             Sutra (optional) ────────────────────────────── LayoutMerger ◄─────────────┘
                                                                  │
                                                           EffectiveLayout
                                                  ("Sutra irs-vanilla v3 + inference")
```

## Rules

| Rule | Recognises | Proposes | Score |
|---|---|---|---|
| `LegsRule` | 2–4 similar objects with at least 5 fields (legs, tranches) | `tabs` with an inferred `kv` body | 0.90 |
| `TermStructureRule` | 3 or more rows keyed by a tenor or contract month (`1M`, `5Y`, `Z6`) with numbers | `line` (one series), or `area` with series (several series, e.g. EE/PFE) | 0.82 / 0.85; 0.50 when the value is money-like |
| `DistributionRule` | 2–12 rows of exactly label + number | `hbar` | 0.80 for money or sensitivities, else 0.45 |
| `TimeSeriesRule` | 3 or more rows led by an ISO date, in date order | `ladder`, latest row highlighted | 0.72 |
| `HomogeneousArrayRule` | an array of objects | `table`, with totals on signed money columns | 0.55–0.65 |
| `NestedObjectRule` | an object with at least 2 scalar fields | `kv` (right column when it has 6 fields or fewer) | 0.55 |

Rules look at top-level arrays and at arrays inside the first element of small object arrays
(`$.legs[0].cashflows`). When several rules claim the same path, the highest score wins.

## Packing

- **Strip:** up to 8 top-level scalars, chosen by role weight and shown in document order. Title
  fields are excluded. The first `mtm` field is emphasised.
- **Main column:** up to 6 panels, in document order. They get `F2`–`F6`. *How this view was built*
  always closes the column.
- **Right column:** up to 3 panels plus *Linked entities*, which goes after the first chart.
- **Title:** the pill is the humanised kind plus the product. The ID is the first of `{kind}Id`,
  `id`, `tradeId`, `code`, `name`, then any `…Id` text field. `with` is a `link(...)` to the
  counterparty.

## Merging with a Sutra

The Sutra always wins. Inference only fills in:
- columns of `table`, `ladder` and `kv` panels that state none, or that say `infer: true`;
- the `kv` body of `tabs` when the body has no columns.

The label reads `Sutra <name> v<N> + inference` whenever inference contributed (including the
linked-entities panel). A document with no Sutra is labelled `inference only`.

## Semantic hints

`drishti-inference/src/main/resources/inference/semantics.yaml` maps field names (by regex) and
value classes to **roles**, each with a format, a tone and a strip weight. For example:
- `mtm`, `pv` and `dv01` are *signed money*, shown with a sign and tone;
- `notional` is *money*;
- `fixedRate` below 1 is a *rate*, formatted `pct4`;
- `…Date` holding an ISO date is a *date*.

The same file sets the tenor pattern, the ID field names and the density limits. A site can replace
it with `drishti.inference.semantics-file`. Role lookups are memoised per field name and value class.
