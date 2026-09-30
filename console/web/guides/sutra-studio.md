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
# Tutorial 3 · Sutra Studio

Studio (`/studio`) is where layouts are written. You edit a Sutra on the left and see it applied to a
real entity on the right. Nothing is saved until you press **Save**.

## 1. Start from something that works

Choose a Sutra in the picker, or type an entity (`trade` / `IRS-47102`) and press **Start from
inference**. Studio loads what inference makes of that entity as an editable Sutra, so you begin
from a working layout.

## 2. Edit and preview

Change a title, a format or a panel `kind`, then press **Ctrl+Enter**. The preview takes tens of
milliseconds and uses the same renderer as the terminal. Keys, fields and expressions are highlighted.

## 3. Fix problems by line

If something is wrong, the list under the editor shows each problem with its code and line (for
example `DRS-2101 line 12: expression … does not compile`). Click a problem to jump to its line.

## 4. Save

**Save** is available to users with the `author` or `admin` role, where saving is switched on
(`DRISHTI_STUDIO_SAVE=true`). Views use the new version immediately.

!!! note "In production"
    Most teams keep saving off and move Sutras through version control. Studio is then a safe place to
    try changes: previews never affect anyone else.
