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
# User guide

## Opening things

Open the terminal at `/t`, or use **Open the terminal** on the landing page. Type a command:

```
TRD IRS-48213 <GO>        a trade
NSET NS-NORTH-01 <GO>     a netting set
CRV USD-SOFR <GO>         a curve
IRS-48213                 a bare identifier works when its pattern names the kind
```

Mnemonics: `TRD` trade · `NSET` netting set · `CSA` credit support annex · `AGR` master agreement ·
`CRV` curve · `CPTY` counterparty · `LIM` credit limit · `CLR` clearing account · `SPEC` contract
spec · `IDX` rate index · `FXS` FX spot · `BOOK` book. Sites add their own in `drishti.commands.mnemonics`.

### Suggestions as you type

The command line suggests in a dropdown, as the Bloomberg terminal does:
- **Empty:** your recently opened entities.
- **A partial word:** matching mnemonics (`T` → `TRD`), your recents, and matching entities of any kind.
- **A mnemonic, then text:** entities of that kind (`TRD IRS-4` → `IRS-47102`, `IRS-48213`, …).

Matched characters are highlighted.

## Keyboard

| Key | Does |
|---|---|
| `F1` | help for the screen you are on |
| `/` | focus the command line from anywhere |
| `↑` `↓` | move through suggestions |
| `Tab` | complete the highlighted suggestion |
| `Enter` | open it (`<GO>`) |
| `Esc` | close the suggestions or the raw drawer |
| `F2`–`F6` | jump to a panel (shown in each panel's header and the footer) |
| `F7` | open the main linked entity (netting set, clearing account, counterparty) |
| `F8` | impact (a later wave) |
| `F9` | raw JSON of the entity, with its source and generation |
| `Alt+←` | back |

## Reading a view

- **Title line:** what the entity is (the pill), its identifier, and its counterparty. The counterparty is a link.
- **Strip:** the key figures. The highlighted one is what the desk watches most (MTM, last price, PFE).
  Negative values use a true minus sign and the negative colour; positive values carry a `+`.
- **Panels:** main detail on the left; context on the right (curve, linked entities, sensitivities).
  An *inferred* tag means inference laid the panel out; hover over it to see why.
- **Linked entities:** every identifier is a door. The badge beside a link (`EE 4.1m`,
  `threshold 0`, `live`) is read from the target. *pending* means it didn't arrive in time.
- **Breadcrumbs:** `← IRS-48213 / NS-NORTH-01` shows the path you followed in this browser tab.
- **How this view was built:** the Sutra and version (or *inference only*), the data fingerprint,
  and the source with its generation.

## Help

**Help** in the top bar (or `F1`) opens the help centre: three tutorials, guides and every reference,
with search. The **?** in each panel header explains that panel kind. **About** shows the version, the
loaded Sutras and the health of each source.

## Themes

The palette menu offers five themes:
- **Terminal** (default);
- **Parchment** (light);
- **Wall Street** (the Bloomberg Terminal colour scheme: black, amber, yellow);
- **Blue** and **Green**.

The choice is remembered per browser.
