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
# ADR-010: Industries are domain packs

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
The engine was always domain-neutral, but the finance vocabulary had spread into the core
configuration: mnemonics, reference patterns, badges, roles, semantic hints, formats, samples, starters,
tutorials and placeholders. "Any domain" was true of the engine and not of the product.

## Decision
Everything industry-specific lives in a **domain pack** (`config/packs/<name>/`). A `pack.yaml` declares the
vocabulary, next to the pack's Sutras, hints, formats, samples, starters and guides.

The `drishti-packs` module turns the enabled packs into the lowest-precedence property source through
an `EnvironmentPostProcessor`, so no core module depends on packs and site configuration always
overrides them. Pack clashes are start-up errors. The console asks the server which packs are
enabled.

Finance and logistics ship as packs; the second one proves the core is neutral.

## Consequences
- A new industry is content and configuration, with no code.
- The core semantic hints and formats are neutral, so an unrecognised domain still renders sensibly
  but more plainly until its pack adds vocabulary.
- Deployments must ship the `config/packs/` folder (both images copy it).
