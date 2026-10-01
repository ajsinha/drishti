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
# Layout mode · arrange a view

A Sutra places a view's panels the same way for everyone. **Layout mode** arranges them for you alone: the panels you
read first at the top, a wide table, a narrow chart beside it, a panel moved to the side column, one you never read
hidden. Your arrangement is kept on the server as your **personal layout** of that Sutra: every entity the Sutra lays
out opens your way, and nobody else's screen changes.

## Tutorial: cashflows first

With the finance pack enabled, open `TRD IRS-48213`.

1. Press `Alt+L` (`Option+L` on a Mac), or click **Alt+L Layout** in the footer. A bar appears: *Layout mode*. Every
   panel is outlined and shows its size at the bottom right, `12/12`: twelve of the twelve columns of its column.
2. Drag *Cashflows · Leg 1* by its heading above *Legs*. A dashed box shows where it will land; let go.
3. Drag the panel's right edge to the left until the badge reads `8/12`, and its bottom edge down to `8/12 · 8 rows`.
4. Drag *DV01 by tenor (USD)* from the side column into the main column and narrow it to `4/12`.
5. Press **Save** (or `Ctrl+Enter`). The footer key now reads `Alt+L Layout •`: you are looking at your own layout.

Open another vanilla swap, or reload: the same arrangement.

![Layout mode on TRD IRS-48213: cashflows 8 columns wide and 8 rows tall beside DV01 by tenor, 4 columns](/static/img/guide/layout-mode.png)

## The keys

On a panel that has the focus (`Tab` moves between panels in layout mode):

| Key | Does |
|---|---|
| `↑` `↓` | One place earlier or later in its column |
| `←` `→` | To the main column, or to the side column |
| `Shift`+`←` `→` | One column narrower or wider (`-` and `+` too) |
| `Shift`+`↑` `↓` | One row shorter or taller |
| `A` | Its natural height again |
| `H` | Hide it, or show it again |
| `Ctrl`+`Enter` | Save |
| `Esc` | Cancel: everything goes back as it was |

Each change is read out by screen readers.

!!! tip "Back to the Sutra"
    **Reset to <Sutra>** in the bar arranges the panels as the Sutra has them. **Save** then forgets your layout.

## Things to know

- **Who may.** A role with the *layouts* power: every role has it unless an administrator unticks it, except the
  built-in `viewer`. Without it, the key is greyed with the reason.
- **Printing** and **workspace panes** follow your layout. Exports, Calc and the API do not depend on it.
- **When the Sutra changes**, panels it dropped leave your layout and new ones appear at the end of their column.
- **Authors** see **Promote to Sutra…**: your layout as the next version of the Sutra, shown as a diff, then submitted
  for review like a Studio save.

More in the [user guide](using-the-terminal) (*Layout mode*), and the panel options `span` and `height` in the
[Rachana reference](rachana-reference).
