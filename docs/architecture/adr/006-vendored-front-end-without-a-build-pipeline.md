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
# ADR-006: Vendored front end without a build pipeline

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Desks can be air-gapped, and a JS build chain adds weight.

## Decision
The console is FastAPI + Jinja2 with vendored Bootstrap, Bootstrap Icons and ECharts. There is no CDN, no npm and no inline script (strict CSP). The file layout follows MAYA.

## Consequences
The console works offline. Upgrades mean copying files deliberately.
