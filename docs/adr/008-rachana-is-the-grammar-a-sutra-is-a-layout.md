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
# ADR-008: Rachana is the grammar, a Sutra is a layout

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
The product concept names the declarative screen grammar **Rachana** (रचना, *composition*): "so no product ever gets its own coded screen". The reference mockups label each view's layout **Sutra** (`Layout: Sutra irs-vanilla v3 + inference`). Waves 4–7 were built under the name Sutra alone.

## Decision
- **Rachana** is the grammar: the language, its schema, its expression language (**Rachana-EL**) and the module `drishti-rachana` (packages `com.ash.drishti.rachana`), configured under `drishti.rachana.*`, documented in `RACHANA_REFERENCE.md`.
- A **Sutra** is one layout written in Rachana: a versioned file `sutras/<domain>/<name>.v<N>.yaml`, modelled by `Sutra` and loaded by `SutraRegistry`.

## Consequences
Both names mean what the concept and the mockups say. The views keep the mockups' wording (`Sutra irs-vanilla v3 + inference`). Renaming touched only module, package, configuration and document names; no behaviour changed.
