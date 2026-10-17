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
# Boards: a wall of live tiles

A **board** is a wall of tiles for a screen on the wall of a trading desk, a risk team or an operations room. Each tile is one visual of a report; the board lays them out, keeps them up to date as the data ticks, turns its pages by itself in **kiosk** mode, and colours a tile amber or red when its number crosses a threshold. This is the reference for boards in Rūpaka **phase 5**. For the live tables behind them see [DATASETS.md](DATASETS.md#71-live-tables-phase-5); for the visuals see [REPORTS.md](REPORTS.md#13-live-visuals-and-perspective-phase-5); for the pages see [BI_GUIDE.md](BI_GUIDE.md) section 18 (and section 1 for the whole picture); for a walk-through with pictures see [tutorial 12](../tutorials/12-live-bi-and-boards.md); the design is [RUPAKA.md](../architecture/RUPAKA.md) section 12 and what is built is its section 20.5.

## Contents

1. [What a board is, and is not](#1-what-a-board-is-and-is-not)
2. [The file](#2-the-file)
3. [Tiles](#3-tiles)
4. [Alerts: a tile changes colour, and can tell people](#4-alerts-a-tile-changes-colour)
5. [The viewer, kiosk and rotation](#5-the-viewer-kiosk-and-rotation)
6. [Who sees what](#6-who-sees-what)
7. [Checking a board: DRS-9052 and DRS-9053](#7-checking-a-board-drs-9052-and-drs-9053)
8. [Settings](#8-settings)
9. [API](#9-api)
10. [Built and not built](#10-built-and-not-built)
11. [Designing a board](#11-designing-a-board)

## 1. What a board is, and is not

* A board **holds no data and no query**. A tile names a report and one of its visuals; the viewer asks that report for the tile's data, **as the person looking**, so the report's dataset, the reader's row rules and masks apply to every tile. A tile can never show more than the same visual in its own report.
* Tiles can come from **several reports over several datasets**: the sample board shows the trading desk and the counterparty exposure on one wall.
* Tiles over a **live table** redraw in place as the table changes; tiles over a cached table show the data as it was read (a board is not "live" unless its tiles are).
* A board is a BI item of kind `board`: a YAML document stored, versioned, reviewed and published like a report (phase 1 and 4), or **shipped** read-only in `config/bi/boards` (watched live: add, change or delete a file and the catalogue follows with no restart).
* A board **sends nothing by itself**. Colouring a tile is what an `alert:` does; a tile tells people only when its alert says `notify:` (section 4, *Notifications*): then everyone who may read the board is told, each on their own number, in their inbox and, if the board says so, by email and the chat bridges.

## 2. The file

`config/bi/boards/<id>.board.yaml`, or the body of a `board` item. The sample, `desk-wall` ([config/bi/boards/desk-wall.board.yaml](../../config/bi/boards/desk-wall.board.yaml)), is the reference; the keys:

```yaml
rachana: 1
board: desk-wall                 # the name: letters, digits, hyphen, underscore, dot (up to 64)
version: 1
title: Desk wall
description: The trading desk and the counterparty exposure on one live wall.
sample: true                     # shipped sample content: hidden from business users when "Hide samples" is on
endorsement: promoted            # the catalogue keys of every shipped file: owner, tags, audience, endorsement
rotate: 20s                      # kiosk mode: how long a page shows before the next one (3s to 1h); default bi.board.rotate_seconds

pages:
  - id: desk
    title: Trading desk
    tiles:
      - { id: net-mtm, report: live-trading-desk, visual: mtm, title: Net MTM, at: { x: 0, y: 0, w: 3, h: 3 },
          alert: { on: MTM, warn: { op: lt, value: 150000000 }, crit: { op: lt, value: 100000000 } } }
```

| Key | Meaning |
|---|---|
| `board` | the board's name (required) |
| `title`, `description` | for the catalogue and the page |
| `rotate` | seconds (`20s`, `2m`, `1h` or ISO `PT20S`) each page shows in kiosk mode; 3 s to 1 h |
| `pages` | 1 to 20 pages; each has an `id` (letters, digits, hyphen, underscore), a `title` and `tiles` |
| `tiles` | 1 to 48 tiles on a page (section 3) |

Unknown keys are **warnings** (ignored), never errors. A file that does not parse keeps its last good version in the shipped folder and is reported in Admin, BI settings and on Health, like every shipped file.

## 3. Tiles

| Key | Meaning |
|---|---|
| `id` | the tile's id on its page (default `t1`, `t2` ...); unique on the page |
| `report` | the report the visual is in: its catalogue id (`file:report:live-trading-desk`), its file name (`live-trading-desk`), or its title. Only reports **the caller may open** resolve |
| `visual` | the id of a visual of that report |
| `page` | the report's page that holds the visual; empty means the first page that has it |
| `title` | replaces the visual's own title on the wall |
| `at` | `{ x, y, w, h }` on the same **12-column grid** as a report: `x` 0 to 11, `w` 1 to 12 with `x + w` at most 12, `y` 0 or more, `h` 1 to 40 rows of 56 px |
| `alert` | the thresholds, and `notify:` (section 4) |

Any visual draws as a tile except a **slicer** (a control, not a picture: refused, `DRS-9052`). A tile is not interactive: nothing a person clicks on a tile filters another tile, and there is no drill. Python tiles run Python for you (below). A 2.1 Perspective tile is drawn as Drishti's grid of its rows (2.3.0: no WebAssembly on any BI page, [REPORTS.md](REPORTS.md#13-live-visuals-and-perspective-phase-5)); a tile on a model follows its pushed answers with a state chip, and the board has the time picker ([BI_GUIDE.md section 25.1](BI_GUIDE.md#251-in-the-console-reports-boards-and-alerts-follow-pushed-answers)); a **rolling chart** keeps its points in the browser for as long as the board is open.

A tile whose report the reader may not open, or that has lost its visual, shows **Not available** with the reason; the rest of the wall draws.

### Python tiles

A tile may be a **python visual** of its report: a Python measure shown as bars, a Python visual that returns a chart, or one that returns a Vega-Lite specification. The tile runs the item for the person looking, exactly as the report does (`format.run` of the visual: the console's sandbox or the browser's Pyodide; [PYTHON_BI.md](PYTHON_BI.md)), and shows the line *Python · item · target · ms · rows* under it. The sample board `python-wall` ([config/bi/boards/python-wall.board.yaml](../../config/bi/boards/python-wall.board.yaml)) has three:

```yaml
- { id: vol,   report: python-desk-risk, visual: volatility, title: Desk P&L volatility, at: { x: 0, y: 0, w: 6, h: 6 } }
- { id: hist,  report: python-desk-risk, visual: histogram,  title: One-day P&L,          at: { x: 6, y: 0, w: 6, h: 6 } }
- { id: strip, report: python-desk-risk, visual: strip,      title: P&L by desk,           at: { x: 0, y: 6, w: 12, h: 6 } }
```

![The Python wall: a Python measure, a Python histogram and a Python visual that returns Vega-Lite, as tiles](../tutorials/img/t12/17-python-wall.jpg)

A tile of a `vega-lite` visual works the same way as any chart tile; a 2.1 `perspective-*` visual draws as Drishti's own grid of the server's answer (no WebAssembly since 2.3.0). A Python tile has **no `alert:` of its own**: the colour rule reads the numbers a query returns, and a Python tile's number is made by code that runs for the person looking. To be told when a Python measure crosses a limit, make an **alert** on it: the server reads it for each person on its schedule or after a load ([DISTRIBUTION.md section 4.1](DISTRIBUTION.md#41-what-can-be-watched), [PYTHON_BI.md section 16](PYTHON_BI.md#16-python-in-alerts-and-on-boards)).

## 4. Alerts: a tile changes colour

```yaml
alert: { on: MTM, warn: { op: lt, value: 150000000 }, crit: { op: lt, value: 100000000 } }
```

| Key | Meaning |
|---|---|
| `on` | the measure or column whose values are compared; empty means the first number column the tile shows |
| `warn`, `crit` | `{ op, value }` with `op` one of `lt le gt ge eq ne` and a number; at least one of them |
| `hysteresis` | a number of 0 or more (default 0): how far back inside a threshold the value must be before the tile **drops a level**. A tile that turned red at 100 000 000 stays red until the value is 5 000 000 back inside the threshold, so a value hovering at the edge does not flip the colour (the same idea as a measure alert's hysteresis, [DISTRIBUTION.md](DISTRIBUTION.md)). Without it a tile follows the value exactly |

The browser evaluates the rule on every redraw against the tile's rows: a card or KPI by its one value, a chart or table by **the worst of its rows**. `crit` is tested first, then `warn`. A tile that reaches a level gets a **coloured border** (amber for warn, red for crit, from the theme's tokens), its number takes the colour, and the line under it says **warning** or **critical** in words: the state never depends on colour alone. `data-alert="warn"` or `"crit"` on the tile's element is what tests and scripts read. Nothing is sent.

**Hysteresis, exactly.** With `hysteresis: h` the thresholds are also tried `h` looser (`lt` and `le` thresholds up by `h`, `gt` and `ge` down by `h`; `eq` and `ne` are not moved). A tile's level is the strict level of the value, or the level it had before as far as the looser thresholds still hold it, whichever is higher. So with `warn: lt 100`, `crit: lt 50`, `hysteresis: 10`: a value of 49 is red; 55 is still red; 60 is amber; 105 is still amber; 110 is normal; and a tile that was normal at 105 stays normal. The browser remembers the level of each tile between redraws; the **notification** uses the same rule (the tile's alert is active until the looser level drops below the level it fired at), so a tile that hovers at the edge neither flickers nor sends a message each time. (The browser's rule is `bi-live.js` `level`, the server's `TileLevel`; a test runs both.)

Thresholds are compared with the value the reader's own query returns, so a reader whose row rules show only their desk sees a tile turn colour on **their** numbers. A masked value (a number the reader may not see) is `null` and never crosses a threshold.

### Notifications: a tile tells people when it turns red

Add `notify` to a tile's `alert` and the **server** (not a browser) judges the tile for every person who may read the board and tells them when it enters the colour. It is the same machinery as an
alert on a number ([DISTRIBUTION.md section 4.8](DISTRIBUTION.md#48-board-notifications) is the reference, with a real run): per-person evaluation under their own masks and row rules, a cooldown, delivery to the inbox, email
and the chat bridges, a history, and an administrators' overview at `/alerts/all`.

```yaml
notify:                              # optional, top level: defaults for every tile that says `notify`
  channels: [inbox, email]
  cooldown: 1h
pages:
  - id: p1
    tiles:
      - id: mtm
        report: trades-overview
        visual: mtm
        at: { x: 0, y: 0, w: 6, h: 3 }
        alert:
          warn: { op: lt, value: -100000 }
          crit: { op: lt, value: -300000 }
          notify: true               # or { level: warn, channels: [chat], trigger: live, severity: critical, cooldown: 30m }
```

| Key | Default | Meaning |
|---|---|---|
| `notify: true` | | take the board's `notify:` defaults (or the built-in ones) |
| `level` | `crit` | `crit` notifies on red; `warn` on amber or red. `crit` needs a `crit` threshold on the tile; `warn` needs a `warn` or a `crit` one |
| `channels` | `[inbox]` | any of `inbox`, `email`, `chat` |
| `cooldown` | the server's `drishti.alerts.measure.cooldown` (30 min) | no second notification for one person within it |
| `trigger` | `after-load` | `after-load` (shortly after a data load announced ready), `live` (when a live report changes) or a schedule (`business-days 07:30`) |
| `severity` | `critical` for `crit`, else `warn` | `info`, `warn`, `critical` |

A person is told when the tile **enters** the level, and again only after it has dropped below it and the cooldown has passed. A person whose number for the tile is masked is told nothing. The message is the tile and its
colour, never a figure (`Desk wall · Mark to market — is now red`); it opens the board, where each person sees their own number. A board with more readers than `drishti.alerts.measure.max-audience` (300) tells nobody
and says so in the history. `notify: false` (or no `notify`) keeps a tile silent.

### Notifications: a tile tells people when it turns red

Add `notify` to a tile's `alert` and the **server** (not a browser) judges the tile for every person who may read the board and tells them when it enters the colour. It is the same machinery as an
alert on a number ([DISTRIBUTION.md section 4.8](DISTRIBUTION.md#48-board-notifications) is the reference, with a real run): per-person evaluation under their own masks and row rules, a cooldown, delivery to the inbox, email
and the chat bridges, a history, and an administrators' overview at `/alerts/all`.

```yaml
notify:                              # optional, top level: defaults for every tile that says `notify`
  channels: [inbox, email]
  cooldown: 1h
pages:
  - id: p1
    tiles:
      - id: mtm
        report: trades-overview
        visual: mtm
        at: { x: 0, y: 0, w: 6, h: 3 }
        alert:
          warn: { op: lt, value: -100000 }
          crit: { op: lt, value: -300000 }
          notify: true               # or { level: warn, channels: [chat], trigger: live, severity: critical, cooldown: 30m }
```

| Key | Default | Meaning |
|---|---|---|
| `notify: true` | | take the board's `notify:` defaults (or the built-in ones) |
| `level` | `crit` | `crit` notifies on red; `warn` on amber or red. `crit` needs a `crit` threshold on the tile; `warn` needs a `warn` or a `crit` one |
| `channels` | `[inbox]` | any of `inbox`, `email`, `chat` |
| `cooldown` | the server's `drishti.alerts.measure.cooldown` (30 min) | no second notification for one person within it |
| `trigger` | `after-load` | `after-load` (shortly after a data load announced ready), `live` (when a live report changes) or a schedule (`business-days 07:30`) |
| `severity` | `critical` for `crit`, else `warn` | `info`, `warn`, `critical` |

A person is told when the tile **enters** the level, and again only after it has dropped below it and the cooldown has passed. A person whose number for the tile is masked is told nothing. The message is the tile and its
colour, never a figure (`Desk wall · Mark to market — is now red`); it opens the board, where each person sees their own number. A board with more readers than `drishti.alerts.measure.max-audience` (300) tells nobody
and says so in the history. `notify: false` (or no `notify`) keeps a tile silent.

## 5. The viewer, kiosk and rotation

Open a board at `/bi/b/<id>` (from the catalogue, then the item page's **Open the board**; `BI desk wall <GO>` opens that item page; or type the address). The page has the title, the **pages as tabs**, previous and next buttons, the **live indicator** with Pause and Resume ([BI_GUIDE.md](BI_GUIDE.md#18-live-reports-and-boards-phase-5)) and the **Kiosk** button.

![The Desk wall: two tiles amber, the rest live](img/bi-live/board-wall.png)

**Kiosk.** The Kiosk button (or the key `k`, or `?kiosk=1` on the address) hides the top bar, the left rail and the heading, fills the screen with the wall, and **turns the pages by itself** every `rotate` seconds (the board's own, else `bi.board.rotate_seconds`, 30). Leave with the button or `Esc`. The button also asks the browser for full screen (a browser only grants that to a click, so `?kiosk=1` on its own hides the menus but leaves the browser's own frame).

![The same wall in kiosk mode](img/bi-live/board-kiosk.png)

| Key | Does |
|---|---|
| `k` | enter or leave kiosk |
| `Esc` | leave kiosk |
| `Left`, `Right` | previous and next page |
| `p` | pause or resume the updates, and the turning of pages |

Rotation stops while the updates are paused and while the tab is hidden, so a wall does not wander off while someone is reading it. The page is addressable: `/bi/b/<id>#exposure` opens the page with id `exposure`.

![The second page: counterparty exposure from another report over another dataset](img/bi-live/board-exposure.png)

On a phone the wall is **one column** in reading order, the buttons are 44 px, and kiosk is the same wall without the menus.

![The board at 390 px](img/bi-live/phone-board-390.png)

## 6. Who sees what

* To open `/bi/b/<id>` a person needs the `bi` power and the right to read the board (workspace role, audience, shipped file), like any item. Without the power the page is the refusal.
* Each tile's data is the report's, so **row rules and masks are the reader's own, on every update**. Two people on one wall see two answers.
* A tile never reveals the existence of a report the reader cannot open: it says "You cannot open the report this tile shows, or it does not exist."
* **A draft report shows only for its author.** In the designer a board can name a report that is still a draft (your own, or one in a workspace you belong to): the tile draws for you and the people who can open that draft, and for nobody else, so a board that shows one **cannot be published or proposed** until the report is published (below). Once a board is published every report it names is published too.
* The board's stream is the **reports'** streams (one per distinct report): a board of ten tiles over two reports holds two subscriptions on the browser's one channel, not ten.

## 7. Checking a board: DRS-9052 and DRS-9053

`POST /api/v1/bi/boards/validate` (authors), the publish check and the BI settings page list every problem with its code and path. Publishing a board checks its **structure**; what its tiles name inside their reports is checked when the board is read and by the validate call.

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-9052` | 422 | the YAML is malformed, a key or id is wrong, a tile is off the grid, an alert threshold is not `{ op, value }`, a tile names a report the caller cannot open or a page or visual it does not have, or the visual is a slicer |
| `DRS-9052` | 422 | (also) a `notify:` with a `level` that is not `crit` or `warn`, a channel that is not `inbox`, `email` or `chat`, a `cooldown` or a `trigger` that is not readable, a `severity` that is not `info`, `warn` or `critical`, or a level the tile has no threshold for |
| `DRS-9053` | 501 | the board uses a setting the grammar reserves and this server does not build: `notifications` or `subscriptions` (on the board or an alert). `notify` is built |

**A board over a draft report is refused at publish.** Publishing or proposing a board whose tile names an unpublished report is `DRS-9052` (force does not help: readers could not open the report), and the message names the report. A real answer, from a server (the board's one tile names the draft report *Draft KPIs*):

```json
{"status": 422, "code": "DRS-9052", "title": "board invalid",
 "detail": "DRS-9052 'KPI board' cannot be published: it shows the report 'Draft KPIs', which is still a draft that only its author can open. Publish it first, or choose a published report."}
```

A page with no tile is also `DRS-9052`, in plain words: `this page has no tiles yet: add a tile (a report and one of its visuals; in YAML { report: <report>, visual: <visual id>, at: { x, y, w, h } })`.

A real answer, for a board with a reserved key and `notify` settings that cannot work (from a real server; the model the server read is cut):

```json
{
  "valid": false,
  "problems": [
    {
      "level": "error",
      "code": "DRS-9053",
      "path": "notifications",
      "message": "'notifications' is reserved and not built: a board notifies through `notify:` on a tile's alert (and defaults in a top-level `notify:`)"
    },
    {
      "level": "error",
      "code": "DRS-9052",
      "path": "pages[0].tiles[0].alert.notify",
      "message": "notify level crit needs a crit threshold on this tile (or set level: warn)"
    },
    {
      "level": "error",
      "code": "DRS-9052",
      "path": "pages[0].tiles[1].alert.notify.level",
      "message": "level is crit (notify on red) or warn (notify on amber or red)"
    },
    {
      "level": "error",
      "code": "DRS-9052",
      "path": "pages[0].tiles[1].alert.notify.channels",
      "message": "'sms' is not a channel: inbox, email, chat"
    }
  ]
}
```

The same call for a tile off the grid, a visual that does not exist and a report that does not exist gives `DRS-9052` with paths such as `pages[0].tiles[0].at`, `pages[0].tiles[0].visual` and `pages[0].tiles[1].report`, and messages such as
`x is 0 to 11 and w is 1 to 12, and x + w is at most 12 (the grid has 12 columns); this is x=0, w=13`, `report 'Live trading desk' has no visual 'nope'` and `no report 'no-such' that you can open (a catalogue id, a file name such as live-trading-desk, or the report's name)`.

## 8. Settings

| Setting | Default | Meaning |
|---|---|---|
| console `bi.board.rotate_seconds` (`DRISHTI_BI_BOARD_ROTATE_SECONDS`) | 30 | kiosk page time when the board sets no `rotate:` (3 to 3600) |
| console `bi.live.min_interval_ms`, `bi.live.stale_seconds` | 250, 30 | how often tiles redraw at most, and when the indicator says "Stale" |
| server `drishti.bi.live.*` | | the live tables and streams a board's reports use ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibilive--streaming-bi-phase-5)) |
| server `drishti.alerts.measure.*` | | notifications from tiles: the load delay, the live debounce, the cooldown, the audience limit, email and chat on or off ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtialertsmeasure--alerts-on-numbers-and-board-notifications)) |
| server `drishti.alerts.measure.*` | | notifications from tiles: the load delay, the live debounce, the cooldown, the audience limit, email and chat on or off ([CONFIGURATION.md](../admin/CONFIGURATION.md#drishtialertsmeasure--alerts-on-numbers-and-board-notifications)) |

## 9. API

| Method and path | Body, answer |
|---|---|
| `GET /api/v1/bi/boards/{id}` | `{id, title, description, status, shipped, sample, valid, problems, board, tiles, mayEdit}`; `board` is the model (defaults filled in), `tiles` maps `"<page id>/<tile id>"` to `{report, reportTitle, page, type, live}` (`report` is null for a tile the caller cannot resolve) |
| `GET /api/v1/bi/boards/reports` | authors; the reports a tile can name: `[{id, title, draft, status}]`, the published ones you may open and then your own drafts (`draft: true`), e.g. `{"id": "bi-1f912abc30", "title": "Draft KPIs", "draft": true, "status": "draft"}` |
| `POST /api/v1/bi/boards/validate` | `{yaml}`; authors; `{valid, problems, board, tiles}`: the model and what each tile resolves to, as the designer needs it |
| any `GET`/`POST` under `/api/v1/bi/boards/{id}` and `/bi/reports/{id}` with `?as=published` | reads the **published** version of what it opens even for an author who has a newer draft (the viewer's *View as readers see it*; data still masked for the person asking). Without it an author gets the draft |
| `POST /api/v1/bi/boards/format` | `{board}` (the model); `{yaml}`: the model written the way a person writes it, one tile to a line (the designer's YAML tab; what it writes is read back to the same board) |

A real answer for the sample board (abridged):

```json
{"status": 200, "valid": true, "rotate": "20s",
 "tiles": {"desk/net-mtm": {"report": "file:report:live-trading-desk", "reportTitle": "Live trading desk", "page": "desk", "type": "card", "live": true},
           "desk/losses":  {"report": "file:report:live-trading-desk", "reportTitle": "Live trading desk", "page": "desk", "type": "card", "live": true}}}
```

The tiles' data comes from the report endpoints (`POST /api/v1/bi/reports/{id}/query`, `GET /api/v1/bi/reports/{id}/live`: [API_GUIDE.md](API_GUIDE.md)). The console passes both through `/bi/api/...` and `/api/channel` (subscription `bilive:<report id>`).

## 10. Built and not built

**Built:** the board file and its parser with every problem coded (`DRS-9052`, `9053`); **notifications from a tile** (`notify:`: inbox, email and chat bridges, each person on their own number, cooldown, history, an administrators' overview) with **hysteresis** (`alert.hysteresis`); **Python tiles** (a python visual as a tile); the **board designer** (section 11); shipped boards (watched) and board items (stored, versioned, reviewed, published; the publish check); the viewer with pages, live redraw in place, the live indicator with pause and resume, per-tile alert colouring by threshold, kiosk mode with auto-rotating pages, grid and rolling-chart tiles; the API; phone layout.

**Not built, and not faked:**

* **A webhook or SMS channel** for a tile (the channels of a tile are inbox, email and chat bridges; webhooks and texts are channels of alerts on measures and of subscriptions: [DISTRIBUTION.md](DISTRIBUTION.md)), **a notification when a tile leaves red** (it re-arms, with hysteresis), a threshold alert on a Python tile (alert on the measure instead), and the board-wide keys `notifications` and `subscriptions`, which stay refused (`DRS-9053`), not ignored.
* **A visual preview of the data in the designer's tiles**: the designer lays tiles out and shows what each is (report, visual, type) and its alert; the numbers are in the viewer (**View**).
* **Interaction between tiles** (clicking a mark filters other tiles), slicers on a board, drill-down and drill-through from a tile.
* **Lineage and impact for boards**: a report change that removes a visual a board uses is found when the board is read (the tile says so), not at the report's publish; boards are not in the lineage panel. A board item can be put in a bundle (by name, or with its workspace), but the reports it shows are not added as dependencies and its body is not checked on import: export the reports with it ([BI_GOVERNANCE.md section 7](BI_GOVERNANCE.md#7-environments-bundles)).
* **Per-tile refresh settings**: a board redraws its live tiles together, at the console's `min_interval_ms`.
* **A tile of a report that has no live table** is drawn once; it is not re-read on a timer.
* **Per-screen layouts**: a phone stacks the grid in reading order; there are no different layouts per device.

## 11. Designing a board

*Designer* in the BI rail starts one (**Boards**: a new board, and your board drafts); a board's page has **Design** (when you may edit it; a shipped board opens read-only, and *Save a copy* makes a draft of your own) and so has its item page (*Open in the designer*). The page is `/bi/design/board/<id>`.

* **The grid.** A 12-column grid of the board's tiles for the current page. Drag a tile by its header to move it, drag the corner to resize it, or focus it and use the arrow keys (Shift with the arrows resizes) and Delete; tiles never overlap (the ones in the way move down). Each tile shows the report, visual and type it names and its alert in words; one that names a report or visual you cannot open is marked.
* **Pages.** The page tabs add a page (up to 20), and *This page* renames, moves earlier or later, or deletes it.
* **Add a tile.** Choose a report (the ones you may open, then **your own drafts, each marked *(draft)***) and one of its visuals (slicers are not offered), then *Add tile*: it lands at the bottom of the page in a size that suits its type. If the report has the same visual id on two pages the tile says which. A tile on a draft report says so on its face, and **Check** warns that the board cannot be published until that report is. Adding a visual that is already on the board is allowed: the tile is added at once and a note appears beside *Add tile* ("Added, but this visual is already on the board (Page 1): two tiles of one visual show the same thing"); it is a note, never a block.
* **Place and size by hand or by the numbers.** Dragging a tile by its header or its corner and the *Column / Row / Wide / High* fields are the same edit: after a drag the fields show where the tile is, and a number typed there moves the tile (Undo steps either back).
* **The selected tile** (right pane): its title, its place on the grid (column, row, wide, high) and its **alert**: the number it watches, amber and red thresholds, the **hysteresis**, and whether to tell the people who read the board when it turns (red, or amber or red). *Apply to the tile* writes it; *Remove the alert* clears it.
* **Kiosk.** *Turn the pages every (seconds)* sets `rotate:`, 5 to 3600; blank turns no pages by itself (the kiosk button still fills the screen).
* **YAML.** The YAML tab shows the same draft as text, written by the server (`POST /boards/format`), and a change there shows on the grid once it parses; each is the other's view. Board-wide settings the designer has no field for (`notify:` defaults) are kept as they are.
* **Check, Save, Publish.** *Check* lists the problems (a tile that names a report or visual that is not there, a bad threshold, `DRS-9052`/`9053`); on a board with no tile at all it says **"This board has no tiles yet: add a tile. Choose a report and one of its visuals on the right, then press Add tile."** and how, not a count of errors. Check and Save answer **beside their own button, once** (*Saved as a draft at 14:02...*, *1 error, 1 warning*), not also in the line at the top. The summary counts what the panel lists, the warnings about tiles on a draft report included, and the count on the Check button is hidden when there is nothing to count. *Save* stores the draft (Ctrl+S); undo and redo (Ctrl+Z, Ctrl+Y) step through the changes. A board is **published like any other item**, from its item page (*Publish...*): in a workspace that needs review it is proposed, reviewed and approved, and the audit trail records it ([BI_GOVERNANCE.md](BI_GOVERNANCE.md)). Nothing is published by saving.
* **View: my draft, or as readers see it.** The *View* menu beside the *View* button chooses which version the page opens: **my draft** (what you are editing, as saved) or **as readers see it (published)**, which is greyed until the board has been published. The published page shows a banner (*Showing the published version 3, the way readers see it; your draft has changes that are not shown*) and a link *Back to my draft*; the data on it is drawn with your own masks and row rules, standing in for the reader's.

On a phone every control is at least 44 px and the panes stack under the grid.
