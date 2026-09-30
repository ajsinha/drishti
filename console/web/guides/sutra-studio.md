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

A Sutra is a **Markdown document**: prose for people and AI assistants, with the layout in one fenced
block marked `sutra`. The engine reads only that block (see the Rachana reference, *File format*).

## 1. Start from something that works

Choose a Sutra in the picker, or type an entity (`trade` / `IRS-47102`) and press **Start from
inference**. Studio loads what inference makes of that entity as an editable Sutra, so you begin
from a working layout.

## 2. Edit and preview

Change a title, a format or a panel `kind`, then press **Ctrl+Enter**. The preview takes tens of
milliseconds and uses the same renderer as the terminal. Keys, fields and expressions are highlighted.

## 3. Write it like a document

The editor is a Markdown editor. The toolbar (and Ctrl+B, Ctrl+I, Ctrl+K) adds headings, bold,
italics, code, links, lists, quotes and tables. Inside the `sutra` block, Rachana is highlighted.

- **Insert…** adds a `sutra` block, a strip field, or a panel of any of the thirteen kinds, already
  shaped correctly, at the right place in the block.
- **Jump to…** lists the headings and the `sutra` block; long documents stay easy to move around.
- **Wrap** turns soft wrapping on or off.
- The **Document** tab shows the Sutra rendered as a page: what the help centre and a Git host show.

Explain the *why*: what the reader looks at first, which mockup the layout follows, what each panel
answers. The next author, or an AI assistant asked to change the layout, starts from that.

## 4. Fix problems by line

If something is wrong, the list under the editor shows each problem with its code and line (for
example `DRS-2101 line 12: expression … does not compile`). Click a problem to jump to its line.

## 5. See and paste the data

The **Sample JSON** tab shows the document the Sutra works on:
- **Load entity JSON** fills it with the chosen entity's document, exactly as the source sent it. The
  Sutra's `$.…` paths address this document.
- **Paste** any JSON object there and tick **Preview against this JSON**. The preview then renders
  your document instead of fetching one. This is how to design a layout before a source is
  connected. **Start from inference** uses the pasted document too.

Inside the `sutra` block the syntax is YAML. The values of `bind`, `where`, `rows` and so on are
Rachana-EL expressions. Line numbers in problems are the Markdown file's.

## 6. Save

**Save** is available to users with the `author` or `admin` role, where saving is switched on
(`DRISHTI_STUDIO_SAVE=true`). A Markdown Sutra is saved as `<domain>/<name>.v<N>.sutra.md`. Views use the
new version immediately.

!!! note "In production"
    Most teams keep saving off and move Sutras through version control. Studio is then a safe place to
    try changes: previews never affect anyone else.
