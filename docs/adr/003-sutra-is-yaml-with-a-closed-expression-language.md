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
# ADR-003: Sutra is yaml with a closed expression language

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Layouts need bindings, conditions and small computations without becoming programs.

## Decision
Sutra files are YAML. Bindings use Rachana-EL, a closed, side-effect-free expression language (paths, filters, ternary, arithmetic, a few functions) compiled to closures.

## Consequences
Layouts are reviewable, diffable and safe. Adding a function needs an ADR amendment.

**Amended by ADR-011:** the YAML now lives in the `sutra` block of a Markdown Sutra (`*.sutra.md`).
