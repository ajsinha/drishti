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
# ADR-002: Layout is sutra plus inference

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Hand-built screens per product type do not scale, and new products appear faster than screens are written.

## Decision
A view layout is the merge of a declarative Sutra (optional) and inferred panels, cached by the pair (sutra@version, shape fingerprint).

## Consequences
Unknown data renders on day one. The inference cost is paid once per data shape, and warm requests only bind values.
