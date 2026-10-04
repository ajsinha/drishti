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
# The help-desk pack

Tickets raised by clients and worked by agents. A teaching pack: it is built step by step in
[the pack developer guide](../../../../PACK_DEVELOPER_GUIDE.md).

## Kinds

| Kind | Mnemonic | What it is |
|---|---|---|
| ticket | `TKT` | One support request, from opening to closing |
| agent | `AGT` | A person who works tickets |
| client | `CLI` | An organisation that raises tickets |
| agent-load | `LOAD` | An agent's open tickets, summed from the tickets (derived) |

## Finding things

| Command | Does |
|---|---|
| `TKT <id> <GO>` | Opens that ticket. A bare id (`TKT-…`) works too. |
| `TKT <start of an id> <GO>` | A pick list; one match opens. `*` is a wildcard; case never matters. |
| `TKT <field>=<value> <GO>` | Lists by value: `TKT status=open`, `TKT ageHours > 24 order by ageHours desc`. |
| `TKT <GO>` | Lists every ticket. |
