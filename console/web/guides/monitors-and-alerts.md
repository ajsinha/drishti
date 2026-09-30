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
# Monitors and alerts

## Monitors

A monitor is a **live watchlist**: one row per entity, and each row shows that entity's own key figures
(its strip) as they tick. Rows can be of different kinds: a netting set, a trade and a shipment can sit
together.

- **Monitors** in the top bar (`/m`) lists yours and the starters the enabled packs offer
  (finance: *Credit watch*; logistics: *Fleet watch*). Opening a starter saves a copy to your account.
- **Add** an entity by typing a command or identifier in the box. **×** removes a row.
- All rows share **one** live connection, so a long list costs the browser no more than one view.

## Alerts

A rule watches one entity. It has:
- a condition in Rachana-EL over the entity's document;
- a severity (`info`, `warn` or `critical`);
- an optional message template.

```text
# Examples
$.utilisation > 0.8                     a netting set near its credit limit
$.mtm < -450000                         a trade's MTM below −450k
$.currentC > 8 || $.currentC < 2        a reefer container out of range
```

- **The server checks every rule on every change** of its entity, whether or not anyone has Drishti open.
- A rule **alerts once** when its condition becomes true, and re-arms when it turns false again. A
  condition that stays true does not repeat.
- A rule that is already true when you save it alerts straight away.
- New alerts show as a **toast** and a count on the **bell** in the top bar, on every page. Click the
  bell to allow browser notifications as well.
- **Alert** in a view's title line, or the bell in a monitor row, opens the rule form for that entity.
  The enabled packs suggest rules for each kind; click a suggestion to fill the form.

!!! warning "Keep conditions simple"
    A condition that fails to evaluate (a missing field, say) is skipped, not alerted. Test it by
    saving: an already-true rule alerts at once.

!!! note "Where alerts live"
    Rules are saved to your account on the server. The most recent 200 alerts per user are kept in the
    server's memory, and a restart clears that history (the rules remain).
