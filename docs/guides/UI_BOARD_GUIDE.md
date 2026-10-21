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
# The Board designer (2.7.0)

A **board** is a screen wired from certified **chips** of the Chip Store: each chip has typed **pins**, a **supplier** feeds its data pins,
and **traces** carry one chip's out pin (a selection, a filter) into another chip's in pin. A board reads its data as whoever opens it:
their roles, row rules and masks, always. The design is [UI_BOARD.md](../architecture/UI_BOARD.md); the server routes are in
[API_GUIDE.md](API_GUIDE.md#ui-boards-and-the-chip-store-apiv1ui-270).

Every step below shows what you do in the console and the same thing in the board document (YAML), which the designer's
**YAML / JSON** tab edits directly. Both are one document: change either and the other follows.

**Where:** Build → **Designer** (`/build/new`) → **New board**, or Build → **Boards** (`/build`), the one list of your boards and your
screen designs. A board opens at `/build/board/<name>`; its viewer at `/ui/b/<name>`.

![The designer in Board mode with the book-trades board](img/ui-board/designer.png)

## 1. One designer, two modes

The designer has two modes in one shell, one palette area (the Chip Store) and one YAML / JSON tab:

* **Board**: the canvas, pins and traces, the mapping sheet, live data, the Problems list. This guide.
* **Chip**: authoring a chip. In 2.7.0 it holds the chip's document in the YAML tab, *Save draft*, *Run examples* (the test bench)
  and *Submit for review*; the full chip designer (manual form, pin table, body editor) comes in phase P4b. See [Chip mode](#chip-mode).

A screen design (`/build/d/<id>`) opens as a board with **Open as board** (in its header, or in the Boards list): each panel becomes a
`panel-<kind>` chip. Its bindings are not converted yet (the status line says which); map them in the mapping sheet. **Export Sutra**
writes a board's panel chips back as a Sutra for a pack, until 3.0.

## 2. Name the board

UI: type the name in **Name** (lower case, digits and dashes) and a **Title**.

```yaml
rachana: 1
board: book-trades
title: Book trades
```

## 3. Add chips from the Chip Store

UI: search the **Chip Store** on the left (*Certified only* narrows it); the journal button opens a chip's datasheet and manual (pins, types,
behaviour, limits, examples). **Drag** a chip onto the grid, or focus it and press **Enter** (it goes below the others). In the mapping sheet
on the right, rename the instance (`trades`).

```yaml
chips:
  trades:
    use: filterable-table@2
layout:
  desktop:
    columns: 12
    areas:
      - { chip: trades, at: [1, 1], span: [7, 8] }
```

Move a box by dragging its heading (or the arrow keys), resize it from its corner (or Shift+arrows); Delete removes it.

## 4. Supply and map the pins

UI: in the mapping sheet choose the **Supplier** (*Pack connector or search*, *BI model*, *Live topic*), its **Name**, and an optional
**Query** (JSON). Then map each column of the chip's data pin to a supplier attribute: the lists offer **only compatible attributes** (an
`id` column offers ids, a `measure` column measures; a `many` column takes several).

```yaml
  trades:
    use: filterable-table@2
    supply:
      connector: trading/trade-search
      query: { book: "%{board.params.book}" }
    map:
      rows.id:       TRADE_ID
      rows.date:     TRADE_DATE
      rows.label:    COUNTERPARTY_NAME
      rows.measures: [NOTIONAL, MTM_USD]
```

The attributes come from the pack's search form, or from the BI model's measures and columns. When the console cannot list them, the sheet
takes typed names; the board check and the render have the last word.

## 5. Draw traces

UI: pins are drawn on each box: in pins on the left edge, out pins on the right, coloured by type. **Drag from an out pin to an in pin**; while
you drag, only the compatible in pins light up. By keyboard: Enter on the out pin, then Enter on the in pin (Esc cancels). A selection traced
into a `filter` pin gets its `filter-on` column by itself (the target's id column). Board parameters are out pins on the strip above the grid.

```yaml
traces:
  - from: trades.selected
    to:   ticket.id
  - from: trades.selected
    to:   flows.filter
    transform: { filter-on: rows.trade }
```

The traces list in the mapping sheet shows every trace with a remove button; clicking a wire selects it (Delete removes it).

## 6. Problems in red

The board check runs on every change. A problem paints its box, pin or wire red with its message, and the **Problems** list below the canvas
jumps to each one. **Save** stays disabled while errors remain.

![A bad wire: rows into a filter pin](img/ui-board/bad-wire.png)

```yaml
traces:
  - from: trades.visible     # BC-04: a rowset cannot feed the filter pin flows.filter
    to:   flows.filter
```

## 7. Live data while you design

UI: tick **Live data**. Every box paints its chip with real data, read **as you** through the render API (the same masks and rules a viewer
with your rights gets), after each change. A detail chip shows *Waiting for id* until its master has a selection; a BI question that becomes a
run shows a run card; a failing chip says its code and reason. Live data waits while the board check has errors.

## 8. Phone and desktop

UI: **Phone** shows the phone layout, one column: reorder with the arrows, tick *collapsed* for a chip shown as a heading the person opens.

```yaml
layout:
  phone:
    order: [trades, ticket, flows]
    collapsed: [flows]
```

## 9. Save, revisions, share, copy

* **Save** (Ctrl+S): a new board, or a new revision. Saving is compare-and-set: if someone saved meanwhile, the designer says *Changed
  elsewhere* with **Reload** (the server's `409 DRS-5057`); your text is still in the YAML tab to copy.
* **Revisions…** lists earlier saves; choosing one puts it in the YAML tab, and Save makes it current.
* **Undo / Redo** (Ctrl+Z, Ctrl+Y) step through your changes in this session.
* **Share** names people who may open your board read-only, as themselves; the link is `/ui/b/<board>?owner=<you>`.
* **Copy to workspace** copies it into a BI workspace, owned by you.

## 10. View the board

UI: **View**, or the board's name in Boards. The viewer paints the layout first, then each chip as its supplier answers. Select a row and the
chips that follow it repaint. Every table chip has **Chart** (K). **Phone layout** shows the phone version; on a phone it is the default.

![A rendered board after a selection](img/ui-board/rendered-board.png)

## Chip mode

The **Chip** button (or `/build/chip/<name>`, Build → Designer → **New chip**) switches the same designer to a chip: its document in the YAML /
JSON tab (`chip`, `version`, `manual`, `pins`, `body`), **Open my draft**, **Save draft**, **Run examples** (each example of the manual
rendered on the test bench, as you) and **Submit for review**. A chip picked in the Chip Store opens as a copy to start from. The full chip
designer arrives with phase P4b.

## Keys

| Keys | Does |
|---|---|
| Enter on a chip in the store | Adds it (Board mode) or opens a copy (Chip mode) |
| Arrows, Shift+arrows on a box | Move, resize |
| Enter on an out pin, then on an in pin | Draw a trace (Esc cancels) |
| Delete | Remove the box or the selected wire |
| Ctrl+Z, Ctrl+Y, Ctrl+S | Undo, Redo, Save |
| g b, g n, g c | Boards, a new board, a new chip |
| ? | The keys |
