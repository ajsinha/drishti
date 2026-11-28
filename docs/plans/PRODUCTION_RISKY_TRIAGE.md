<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.
-->

# Production readiness: triage of the 15 risky items (R1..R15)

Companion to PRODUCTION_READINESS_AUDIT.md. Rule: security and anything small go into i8; large redesigns go to i9.
Sizes are the audit's Sonnet lane-hours. "Lane" = the owning area at the time of writing.

| Item | Decision | Why | Size | Owning area |
|---|---|---|---|---|
| R1 implicit localhost targets | i9 (was i8) | Needs the production mode to know when no default is allowed; every dev command and test relies on the default today | 4 | tools / LOAD |
| R2 five names for the server address | i9 (was i8) | Retiring names touches CONFIGURATION.md, deploy/aws, console settings and 4 guides: the production merge lane's files | 3 | tools, PROD |
| R3 four secret mechanisms | i9 (console unit loading server secrets noted for PROD lane) | Needs one reader and vault hook: a design | 8 | PROD |
| R4 three layouts, one unit | i9 | Packaging with B2; belongs to the production merge lane | 4 | PROD |
| R5 console boundary | i9 | Roadmap redesign, other lane in flight | 20 | CON |
| R6 data inside packs | i9 | Large move of ~300 files, DATA lane | 12 | DATA |
| R7 kernel names engines | i9 | R2 engine cut, other lane | 16 | R2 |
| R8 engine required/version | i9 | Manifest redesign, R2 lane | 8 | R2 |
| R9 identity DB upgrade, no migrations | i9 | New start check and verify-only mode; design | 8 | PROD |
| R10 production acceptance | i9 | Depends on production mode and catalogue | 8 | PROD |
| R11 row rules not proven everywhere | i8 (guard part) | Security: route-inventory guard that fails on any unclassified data route; the full matrix test stays i9/i10 | 10 (guard ~3) | kernel-server |
| R12 loader staging in system temp | i8 | Data leaves the data folder (large loads in /tmp); 2 h | 2 | tools/lake |
| R13 plain HTTP + shared key | i8 (warning only) / i9 (asymmetric) | Start-time warning belongs to the production mode (profile lane); listed for the lead | 3 | PROD |
| R14 bindings by pack / module folder | i9 | Table registry lane | 6 | S1 |
| R15 official results follow demo variable | i9 | Table registry lane | 5 | S1 |

Counts: i8 = 3 (R11 guard part, R12, R13 warning handed to the production lead), i9 = 12 (R1 and R2 moved after the first look). Done in i8/risky: R12, R11 guard.
