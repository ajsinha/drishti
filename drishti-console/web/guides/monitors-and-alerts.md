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

Two tools keep an eye on things for you:

- a **monitor** is a live watchlist: one row per entity, each row showing that entity's headline figures as they
  tick;
- an **alert** is a rule on one entity, such as *"tell me when this netting set uses more than 75% of its
  limit"*. The server checks it on every change, even when you are not looking.

## Tutorial: build a watchlist of netting sets near their limits

This uses the counterparty-risk pack. Any pack works the same way.

### 1. Find the entities with a search

Type this on the command line (`/t`) and press Enter:

```text
# Netting sets above 70% utilisation, highest first
NSET where utilisation > 0.7 order by utilisation desc
```

You should see a results table with nine netting sets, led by `NS-CASCADIA-TKY` (about 80%),
`NS-NORTHBRIDGE-NY` and `NS-SUMMIT-LDN`, with a **Utilisation** column.

### 2. Turn the results into a monitor

Above the results there is a name box (filled with *NSET search*) and a **Watch as a monitor** button.

1. Change the name to `Near limit`.
2. Press **Watch as a monitor**.

You should land on `/m/Near limit`: one row per netting set, each showing its strip (*Trades*, *Net MTM*,
*Collateral*, *EE peak*, *PFE 95 peak*, *Limit*, *Utilisation*, *CVA*). Values that change flash briefly.

The monitor keeps the first 50 results. It is a fixed list from now on: a netting set that crosses 70% tomorrow
is not added by itself. Run the search again to refresh it.

### 3. Add and remove rows

- **Add:** type a command or identifier in the *Add* box at the top and press Enter, for example
  `TRD MX-20000001` or just `NS-SUMMIT-NY`. Rows of different kinds can sit together.
- **Remove:** press **×** on a row.
- **Delete monitor** removes the whole list.

A monitor holds 1 to 50 entities, and you can keep up to 50 monitors. All rows share **one** live connection,
so a long list costs your browser no more than a single view.

### 4. Come back to it

**Monitors** in the top bar (`/m`) lists *Yours* first, then *Starters*. Monitors are saved to your account on
the server, so they follow you to any browser.

!!! note "Starter monitors"
    Some packs offer ready-made monitors. The finance pack offers *Credit watch* (`NS-NORTH-01`, `NS-HARB-02`,
    `IRS-48213`, `FXS-20931`, `CFT-77120`); the logistics pack offers *Fleet watch* (`SHP-10042`,
    `CTR-MSKU1234567`, `VSL-9811000`, `PORT-NLRTM`). Opening a starter saves a copy under your name. With
    neither pack enabled, the Starters list says *The enabled packs offer no starter monitors*: build one from a
    search as above.

## Tutorial: your first alert

### 1. Open the rule form for an entity

Open the netting set:

```text
NSET NS-CASCADIA-TKY
```

Press **Alert** in the view's title line (or the bell icon on its row in a monitor). You should land on
`/alerts?kind=netting-set&id=NS-CASCADIA-TKY`, with *Kind* and *Id* already filled in.

### 2. Fill in the rule

| Field | Type | Why |
|---|---|---|
| Name | `Cascadia over 75%` | Saving a rule under a name you already use replaces that rule. |
| Kind, Id | (already filled) | The one entity this rule watches. |
| When | `$.utilisation > 0.75` | A Rachana-EL condition over the entity's document. |
| Severity | `warn` | `info`, `warn` or `critical`: sets the colour of the toast. |
| Message | `utilisation ${fmt($.utilisation, 'pct0')}` | Optional. `${…}` inserts values. Left empty, the message is the rule's name and condition. |

Press **Save rule**. If the condition or message does not compile (a missing quote, say), the rule is refused
with the reason, and nothing is saved.

### 3. See it fire

`NS-CASCADIA-TKY` is already at about 80%, so the condition is true the moment you save. You should see:

- a **toast** in the corner: *warn NS-CASCADIA-TKY utilisation 80%* (severity, entity, your message);
- a **count on the bell** in the top bar;
- a new line under **Recent alerts** on the alerts page (the bell opens the inbox; the alerts page is linked from it, or open `/alerts`).

Click the toast to open the netting set.

!!! tip "Testing a rule"
    Saving is the quickest test. A rule that is already true alerts at once; a rule that is false stays quiet
    until the data changes. To check the field name first, press **F9** on the view and read the raw JSON.

## How alerts behave

- **Checked on every change.** The server evaluates each rule whenever its entity changes, whether or not anyone
  has Drishti open.
- **Alert once, then re-arm.** A rule alerts when its condition *becomes* true. While it stays true, it does not
  repeat. When it turns false again, it re-arms, ready to alert next time.
- **Everywhere you are.** New alerts show as a toast and a bell count on every page of the console. The bell
  counts alerts and what colleagues sent you (shares, mentions, replies; see [Sharing and discussion](/help/sharing-and-discussion)).
  Clicking it (or `Alt+I`) opens your **inbox**; the alerts that fired are on the alerts page, linked from the inbox. The
  first click also asks the browser for permission to show system notifications.
- **Kept for a while.** Your rules are saved to your account. The most recent 200 alerts per user are kept in
  the server's memory; a restart clears that history, but not the rules.
- **Up to 50 rules** per user.

## Conditions you can copy

A condition is a Rachana-EL expression over the entity's document (`$` is the document). Field names are the
document's own: press **F9** on the view to see them.

```text
# Netting sets and limits (counterparty-risk pack)
$.utilisation > 0.8                       near the credit limit
$.netMtm < -1500000                       net MTM below −1.5m
$.pfePeak > $.limit                       PFE peak above the limit
```

```text
# Trades (trading pack)
$.mtm < -450000                           MTM below −450k
$.pnl1d < -100000                         lost more than 100k today
$.confirmation.status != 'Confirmed'      not yet confirmed
```

```text
# Logistics pack
$.etaDelayHours > 12                      a shipment delayed more than 12 hours
$.currentC > 8 || $.currentC < 2          a reefer container out of range
```

Combine with `&&` (and), `||` (or) and `!` (not). Compare text with `==` and quotes: `$.status == 'Live'`.

!!! warning "Keep conditions simple"
    A condition that cannot be evaluated (a misspelt field, say) is skipped, not alerted. If a rule never fires,
    check the field name in the raw JSON (F9).

## Suggested rules

Packs can suggest rules for each kind of entity. They appear under the form as *Suggested by the enabled packs*;
click one to fill the form. The finance pack suggests, for example, *PFE near limit* (`$.utilisation > 0.8`) for
netting sets and *MTM below −450k* (`$.mtm < -450000`) for trades. Pack authors add them under `alerts:` in
`pack.yaml`:

```yaml
# pack.yaml (excerpt)
alerts:
  - { kind: netting-set, name: "PFE near limit", when: "$.utilisation > 0.8", severity: warn,
      message: "${$.nettingSetId}: utilisation ${fmt($.utilisation, 'pct0')} of limit" }
```

## Where to go next

- [Workspaces](workspaces): several live views on one screen.
- [Using the terminal](using-the-terminal): search syntax, business dates and the rest of the console.
