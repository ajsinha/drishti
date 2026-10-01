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
# ADR-015: Packs inherit, and the more specific pack wins

| Status | Date | Decider |
|---|---|---|
| Accepted | 2026-09-30 | Ashutosh Sinha |

## Context
Packs already built on each other (`requires:`): a parent loaded first, and its kinds could be opened from the child.
But any two packs defining the same mnemonic, role, route, connector or Sutra stopped the server, so a risk pack
could not adjust what it builds on (another label for MTM, a trader role that also sees VaR, the trading domain read
from PostgreSQL). Real domains layer: counterparty risk *is* trading and market data, with more.

## Decision
- **`extends: [a, b]`** (with `requires:` read the same way) makes a pack inherit everything its parents bring.
- **Precedence is C3 linearisation**, reading the parents rightmost first: the child wins over its parents, the
  rightmost parent over those to its left, and an ancestor shared through several parents counts once, after all of
  them. The server-wide order is the linearisation of every enabled leaf pack together, which C3 keeps consistent
  with each pack's own; an inconsistent order (two packs listing the same parents in opposite orders) or a cycle
  stops the server.
- **Overrides are global**: the winning definition applies to every user, whichever packs they have active (the
  user chose this over per-user layering). Every override is logged and shown under Admin → Health.
- **Strict where it matters**: a kind belongs to exactly one pack and is never overridden; unrelated packs may not
  define the same thing differently.
- Sutras: a more specific pack's Sutra with the same `name@version` replaces the parent's; site Sutra directories
  still take precedence over every pack.

## Consequences
- The banking risk packs declare `extends: [market-data, trading]`.
- A pack author can specialise a parent without forking it; a reader finds what won, and why, in one place.
