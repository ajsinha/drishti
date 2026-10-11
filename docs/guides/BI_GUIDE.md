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
# Business intelligence (Rupaka): the BI page

Rupaka is Drishti's business-intelligence layer. This guide describes **what is built today: phase 1 (the shell and the
catalogue), phase 2 (datasets and the query engine) and phase 3 (reports, the designer and the Model view)**. You can open the BI page, keep drafts in a personal workspace, share workspaces
with roles, put typed items in them, publish them through versions (with an optional second-person review), certify them, and find what you
may open in a catalogue. A **dataset** item is now parsed, validated and **queried**: tables from pack kinds through connectors,
relationships, measures, row rules and masks, answered by an embedded DuckDB (section 13; the reference is [DATASETS.md](DATASETS.md) and the
walk-through [BI_TUTORIAL.md](BI_TUTORIAL.md)). A **report** item is now **drawn**: pages of visuals (bar, line, table, matrix, cards, slicers and more) that
query the dataset as the person looking, with cross-filtering, drill-down and drill-through (section 14; the reference is [REPORTS.md](REPORTS.md)); the **designer** builds one by dragging fields onto a canvas
(section 15) and the **Model view** draws a dataset's relationships as a diagram (section 16). **Boards and notebooks are not run yet**: such an item holds a YAML body that Drishti stores,
versions and publishes but does not execute or draw. Live BI and exploration are later phases; section 11 lists exactly what is and is not
there. The design is in [RUPAKA.md](../architecture/RUPAKA.md); the build plan is its section 20.

## Contents

1. [What phases 1 to 3 are, and are not](#1-what-phases-1-to-3-are-and-are-not)
2. [Getting in: the BI menu and RUPAKA](#2-getting-in-the-bi-menu-and-rupaka)
3. [Who may do what: the four powers](#3-who-may-do-what-the-four-powers)
4. [The page and its rail](#4-the-page-and-its-rail)
5. [Home](#5-home)
6. [The catalogue](#6-the-catalogue)
7. [Workspaces and their roles](#7-workspaces-and-their-roles)
8. [Items, versions, review and endorsement](#8-items-versions-review-and-endorsement)
9. [Who sees what](#9-who-sees-what)
10. [Shipped content, settings and the command line](#10-shipped-content-settings-and-the-command-line)
11. [Built and not built](#11-built-and-not-built)
12. [Problems](#12-problems)
13. [Datasets and the query engine (phase 2)](#13-datasets-and-the-query-engine-phase-2)
14. [Reports: the viewer (phase 3)](#14-reports-the-viewer-phase-3)
15. [The designer (phase 3)](#15-the-designer-phase-3)
16. [The Model view (phase 3)](#16-the-model-view-phase-3)

## 1. What phases 1 to 3 are, and are not

| You can | You cannot yet |
|---|---|
| Open BI from the top bar or with `BI <GO>` | Stream live data into a visual, use Perspective or Python in BI (later phases) |
| Keep drafts in a personal workspace, move them to a shared one | Explore a dataset freely (phase 9) |
| Create typed items (`dataset`, `report`, `board`, `notebook`) with a YAML body | Run a `board` or `notebook` body: it is stored as text. A `dataset` body is validated and queried (section 13), a `report` body is drawn (section 14) |
| Open a dataset: its model, and a **query preview** with the server's row rules and masks | Version-diff a dataset, or see what a change to it breaks (phase 4) |
| Open a report: tabs, slicers, click a bar to filter the page, drill down, drill through, show the rows, open in the terminal (section 14) | Export it, subscribe to it or embed it (phase 7) |
| Design a report by dragging fields onto a canvas, with the YAML beside it, real data and the query time on every visual, Check, undo and redo (section 15) | Have the designer suggest a report from words (stage 2), or save an Explore view as a visual (phase 9) |
| See a dataset's tables and relationships as a diagram and draw a relationship by dragging a column onto another (section 16) | Change a pack from the diagram: it never does |
| Start a dataset from the packs' kinds, links across packs, proposed relationships | Schedule a refresh by cron, time intelligence, Python measures (DATASETS.md section 10) |
| Publish versions, restore an old one, propose for review, approve (four-eyes) | See a diff between versions, or the dataset-change impact (phase 4) |
| Promote or certify items, set an audience of roles, users and packs | Export, subscribe, alert or embed (phase 7) |
| Search, filter and sort a catalogue that shows each person only what they may open | Stream live data (phase 5) |
| Read shipped, read-only content from the BI folder | Deploy a workspace as a pack bundle (phase 4) |

Everything the page does, the server decides: the page is a shell that asks the server's `/api/v1/bi` under your identity.

## 2. Getting in: the BI menu and RUPAKA

![The BI menu in the top bar](img/bi/02-bi-menu.jpg)

- **The BI menu** sits between **Views** and **Build**, with the icon `bar-chart-line`. It lists *BI home*, *My workspace*,
  *Catalogue*, and the three items you opened most recently. It is shown only to people who hold the `bi` power (section 3), and
  not at all when BI is switched off. Administrators with `bi-admin` also see *BI settings*; an administrator also sees the
  *proof of concept* there when `bi.poc_enabled` is on (section 10).
- **`BI` on the command line** is a reserved keyword (`RUPAKA` is an alias and works the same). Case does not matter.

| Type | Opens |
|---|---|
| `BI <GO>` | the BI home (`/bi`) |
| `BI WORKSPACE <GO>` | your personal workspace (`/bi/w/me`) |
| `BI CATALOGUE <GO>` | the catalogue (`/bi/catalogue`) |
| `BI <report-or-board> <GO>` | an item by its id or its title (any case); a **report opens drawn** (`/bi/r/<id>`), any other item opens its page; if the words match several items, the catalogue searched for them (a pick list) |
| `BI SETTINGS <GO>` | Admin, BI settings (needs `bi-admin`) |

![Type-ahead after RUPAKA](img/bi/03-keyword.jpg)

Type-ahead offers the keyword after the first letters (`b`, `ru`), the three pages (`WORKSPACE`, `CATALOGUE`, home) after `BI `, and the items you may open
that match what you type next. Someone without the `bi` power gets no suggestions and, on `<GO>`, a message that says what is
missing. **A pack may not define a mnemonic named `BI` or `RUPAKA`** (or `FORM`, the other reserved word): the pack loader refuses it with
`pack 'x' defines the mnemonic 'BI', which is a reserved command word (BI, FORM, RUPAKA): choose another`, so `pack check` fails
the same way.

URLs: `/bi` (home), `/bi/catalogue`, `/bi/workspaces`, `/bi/w/<workspace>` (`me` is your personal one), `/bi/i/<item>`, `/bi/datasets` and `/bi/d/<dataset>` (phase 2),
`/bi/r/<report>[/<page>]` (the viewer), `/bi/designer` and `/bi/design/<draft>` (the designer), `/bi/m/<dataset>` (the Model view), and the placeholder `/bi/explore`. Admin: `/admin/bi`.

## 3. Who may do what: the four powers

BI powers are granted on roles, beside `raw`, `author` and `approve` (see [USER_MANAGEMENT.md](../admin/USER_MANAGEMENT.md#bi-powers-rupaka)).

| Power | Lets a holder |
|---|---|
| `bi` | open BI, the catalogue and the published content their audience allows; keep favourites and recents |
| `bi-author` | create, edit, move, propose and publish items, and create shared workspaces (if the policy says authors) |
| `bi-steward` | **certify** published items; create shared workspaces when the policy says stewards |
| `bi-admin` | change BI settings, see and manage every shared workspace; always allowed to create workspaces |

A higher power also opens BI, so a role granted only `bi-author` still sees the catalogue. The built-in `admin` role holds all four;
`viewer` holds `bi`; `author` and `approver` hold `bi` and `bi-author`; a role a pack defines holds none until an administrator
grants it. With security off, everyone holds everything. What a power allows is then narrowed by the **workspace role** (section 7).

## 4. The page and its rail

The BI page shares the top bar, sign-in, themes, inbox and help with the terminal, and has its own layout: a left **rail** and a
wide canvas. The terminal's F-key footer is not shown (F1 opens help for BI).

![BI home](img/bi/01-home.jpg)

| Rail entry | Opens | State |
|---|---|---|
| Home | recent, favourites, certified for you, shared with me | built |
| Catalogue | search, filters and sort over what you may open | built |
| My workspace | your personal workspace | built |
| Workspaces | the workspaces you belong to; create one | built |
| Datasets | the semantic models, their pages and the Model view | built (phases 2 and 3) |
| Designer | your report drafts, a new report on a dataset, and the designer | built (phase 3) |
| Explore | free exploration of a dataset | placeholder, "phase 9" |

**Keys** (shown by `?`): `/` search the catalogue (from another BI page it takes you to the catalogue with the search focused),
`g h` home, `g c` catalogue, `g w` my workspace, `?` the list, `Esc` closes it. Keys are ignored while you type in a field.

**On a phone** (390 px) the rail becomes a scrolling bar above the page, cards stack in one column, every control is at least
44 px high, and fields use 16 px text so the browser does not zoom.

![BI on a phone](img/bi/11-phone-home.jpg)

![The catalogue on a phone](img/bi/12-phone-catalogue.jpg) ![A workspace on a phone](img/bi/13-phone-workspace.jpg)

## 5. Home

Four sections, each a set of cards. A card shows the kind, a star (favourite), the title, the description, badges (certified,
promoted, shipped, status, tags) and a line with owner, workspace, version, freshness ("updated 3 days ago") and the views in the
last 30 days.

| Section | Shows |
|---|---|
| **Recent** | what you opened lately (opening an item page counts), newest first |
| **Favourites** | items you starred, by title |
| **Certified for you** | certified items you may open, most used first |
| **Shared with me** | published items in workspaces you belong to that others own, newest first |

"What changed since your last visit", described in the design, is **not built**.

## 6. The catalogue

![The catalogue](img/bi/04-catalogue.jpg)

It lists every **published** item you may open (section 9). Typing in the search box searches title, description, tags, owner,
kind and workspace name (every word must match); the filters narrow by kind, workspace, owner, endorsement, tag and how recently
it was published; sort is *most used* (the default), *newest* or *title*. **Certified items are pinned first** except when sorted
by title. The address keeps the state (`?q=daily&kind=report`), so a link reproduces the list. The filter lists show only values that
exist, with counts.

![A filtered catalogue](img/bi/05-catalogue-filtered.jpg)

Searching "the fields used" and filtering by *domain or pack* and by *live or cached* need datasets and are not built (an item can
name packs in its audience, and the API filters by `pack`, but no page offers it yet).

## 7. Workspaces and their roles

![A workspace](img/bi/06-workspace.jpg)

- **Personal workspace** (one per user, created on first use): drafts only you can see. It has no members and cannot publish: move
  an item to a shared workspace first (*Move to workspace*).
- **Shared workspace**: created from **Workspaces** (needs the policy's power, section 10). The creator becomes its admin. It has
  members with a role:

| Workspace role | May |
|---|---|
| `viewer` | open the workspace and its published items |
| `contributor` | also create and edit drafts (needs the `bi-author` power too) |
| `member` | also publish, approve proposals, promote, delete others' drafts (publish needs `bi-author`) |
| `admin` | also add and remove members, rename it, switch review on or off, delete it (when empty) |

A person who is not a member does not learn that a workspace exists (`404 DRS-9001`). The last admin cannot be demoted or
removed. A workspace can **require review before publishing**: then *Publish* is not offered and an item is *proposed* and approved.
A `bi-admin` is an admin of every shared workspace (but cannot read anyone's personal workspace).

## 8. Items, versions, review and endorsement

![An item](img/bi/07-item.jpg)

An **item** has a kind (`dataset`, `report`, `board`, `notebook`), a title, a description, an owner, tags, an audience, a
**YAML body** and a lifecycle. The body must parse as YAML with a mapping at the top; it may be empty; it is at most
`drishti.bi.max-body-bytes` (256 KiB). Nothing runs it.

**Lifecycle.** `draft` → (optional `proposed`) → `published` as version N.

- Editing a **published** item makes the item a draft again; the published version stays what viewers see, and the page shows
  "draft changes not published".
- **Publish** (member, `bi-author`, shared workspace, no review required) writes the next version and makes the item `published`.
- **Propose for review** (contributor) freezes the item; **Approve and publish** needs a workspace `member` who holds the
  **`approve`** power (the same one that approves Sutras) and is not the proposer when four-eyes is on
  (`drishti.governance.four-eyes`, `DRS-9005` otherwise). **Reject** needs a reason and returns the item to draft; the proposer
  may **withdraw**.

![Proposed for review](img/bi/08-review.jpg)

![Approving](img/bi/09-approve.jpg)

- **Versions** are kept (`drishti.bi.versions-keep`, 50). The page lists them; *View* shows a version's body; *Restore as draft*
  makes an old version the draft again, to be published as a new version. A diff between versions is not built.
- **Endorsement.** The owner or a member may **promote** a published item; only a `bi-steward` may **certify** it, or remove a
  certification. Certified items are pinned first in the catalogue and listed on Home.
- **Favourites and views.** The star is yours alone. Opening an item counts one view for today; the card shows the views of the last
  `drishti.bi.usage-days` (30).
- **Delete** (owner, or a member) removes the item and its versions. **Move** changes workspace (you need contributor in the target).

Every change is written to the audit log (Admin, Audit log): `bi-workspace-created`, `bi-workspace-updated`, `bi-workspace-deleted`,
`bi-member-set`, `bi-member-removed`, `bi-item-created`, `-updated`, `-moved`, `-deleted`, `-proposed`, `-withdrawn`, `-approved`,
`-rejected`, `-published`, `-restored`, `-endorsed`, `bi-settings-changed`.

## 9. Who sees what

An item reaches the **catalogue** and **Home** for you when it is **published**, not in a personal workspace, and at least one holds:

1. you are a `bi-admin`, its owner, or a member of its workspace (any role);
2. its **audience** names one of your roles, you by name, or a pack assigned to you;
3. it is shipped content with no audience (everyone with `bi`).

An item with an empty audience is therefore visible to its workspace's members only. A draft is seen only by the workspace's
contributors and above. Anyone who may not see an item gets `404 DRS-9001` for it, never a hint that it exists. Role names and
packs in an audience are checked when saved. Rows and fields **inside** a dataset (row rules, masks) are phase 2; nothing in
phase 1 returns data, so there is nothing to mask yet.

## 10. Shipped content, settings and the command line

**Shipped content.** Files in the BI folder (`drishti.bi.dir`, default `./config/bi`) appear as published, read-only items of the
workspace *Shipped content*: `datasets/<id>.dataset.yaml`, `reports/<id>.report.yaml`, `boards/<id>.board.yaml`,
`notebooks/<id>.notebook.yaml`. The top-level keys `title`, `description`, `owner`, `tags`, `endorsement` (`none`, `promoted`,
`certified`) and `audience` (`roles`, `users`, `packs`) describe the catalogue entry; the file is the body. The folder is read again
**live, with no restart** (`drishti.bi.watch`: `auto` file events with a polling safety net every `folder-refresh` (5 s), `poll`, or `off`, where the
folder is read on the first request after `folder-refresh`): **add** a file and it appears in the catalogue and in *Shipped content*; **change** it and the next
read has the new content as the next version (`v2`, `v3`, counted since the server started; only the current body is kept); **delete** it and it is gone, and an
open item page says so on its next load (`404 DRS-9001`). A file that does not parse (bad YAML, or not a mapping) **keeps its last good version** and is listed
as a problem, with the message, in *Admin, BI settings* and on *Health* (`bi.problems`); fixing the file clears it. A bad file that never parsed has no item, only the
problem. Shipped items cannot be edited here: change the file. Details: `config/bi/README.md`.

```yaml
# config/bi/reports/liquidity-daily.report.yaml
title: Liquidity daily
description: Shipped with the site, read-only
owner: treasury
tags: [liquidity, daily]
endorsement: promoted
pages: []
```

**Admin, BI settings** (`/admin/bi`, needs `bi-admin`):

![Admin, BI settings](img/bi/10-settings.jpg)

- *Who creates shared workspaces*: `authors` (`bi-author`, the default), `stewards` (`bi-steward`) or `admins` (`bi-admin` only). It
  is kept in the identity database and overrides `drishti.bi.workspace-creation`.
- *The BI folder*: how it is watched (`WATCHING`, `POLLING`, `OFF`), how many shipped items it holds, and any file with a problem.
- *Roles and their powers*: tick `bi`, `bi-author`, `bi-steward`, `bi-admin` per role and **Save**. Roles from configuration or a pack
  (built in) are read-only here; roles defined in Admin, Roles are editable, and only by an administrator (the page calls the
  role-definition API as you).

**Settings** (all in [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibi--rupaka-business-intelligence-phase-1-the-shell-and-the-catalogue)): `drishti.bi.enabled`,
`dir`, `watch`, `folder-refresh`, `workspace-creation`, `max-body-bytes`, `versions-keep`, `usage-days`, `recent`, `list-limit`; the
console's `bi.enabled` (`DRISHTI_BI_ENABLED`) and `bi.poc_enabled`.

**The proof of concept.** The phase 0 page `/bi/poc` is kept for administrators, under the BI menu, and exists only while
`bi.poc_enabled` (console) and `drishti.bi.poc.enabled` (server) are on. It is throw-away code; see
[RUPAKA_POC.md](../architecture/RUPAKA_POC.md). `RUPAKA <GO>` no longer opens it.

**The command line.** `drishti.py bi workspace list|get` and `drishti.py bi catalogue list|get|publish` talk to the API as the
token's user ([CLI_GUIDE.md](CLI_GUIDE.md#bi-workspaces-and-the-catalogue-rupaka)):

```
$ drishti.py bi workspace list
id                            role          items  name
u-cat-7fd6                    admin             0  My workspace  [personal]
rates-desk                    viewer            4  Rates Desk
shipped                       viewer            2  Shipped content
$ drishti.py bi catalogue list --q exposure --kind board
id              kind     endorsed  status    ver  title
bi-cd5f8b2cc5   board    promoted  published v1   Counterparty exposure  (Rates Desk, ann)
1 item(s)
```

**The API.** [API_GUIDE.md](API_GUIDE.md#business-intelligence-rupaka-apiv1bi) lists every endpoint and the token scopes
`bi:write`, `bi:approve` and `bi:admin`.

## 11. Built and not built

Built in phase 1: the BI menu and `RUPAKA` keyword with type-ahead; the shell with its rail and keys; Home and the catalogue;
personal and shared workspaces with four roles; typed items with YAML bodies; versions, restore, review with four-eyes, endorsement;
audience-based visibility; shipped files; Admin, BI settings; four powers on roles; the audit trail; the CLI; DRS-9001 to DRS-9006.

Built in phase 2: the dataset YAML with its parser and validation (DRS-9010 to 9013); the expression language with aggregation; a dataset
started from pack kinds with the packs' links across packs and relationships proposed from the data; the connector SPI `scan` and `sqlTarget`
(Delta, DuckDB, PostgreSQL, file); the query engine (an `AnalyticEngine` interface, embedded DuckDB, compiled parameterised SQL), the columns and
rollup layers rebuilt per business date on data-load signals, `direct` tables pushed to a SQL store, a minimal `live` table; row rules and masks;
Arrow and JSON answers; the result cache; limits (DRS-9020 to 9027); the Datasets list and dataset pages; three sample datasets; compressed static
files. The details, and the list of what is **not** built, are in [DATASETS.md](DATASETS.md) section 10 and [RUPAKA.md](../architecture/RUPAKA.md) section 20.2.

Built in phase 3: the report YAML with its parser, validator and writer (DRS-9030 to 9035); the viewer with 21 visual types, three levels of filters with top-N, slicers, cross-filter and highlight, drill-down,
drill-through, show the rows, bookmarks and palettes; publishing checks a report; the designer (Fields, Visualizations with Build and Format, Filters, pages, the canvas on the 12-column grid with the keyboard, the YAML tab,
real data and query time while designing, Check, undo and redo, save and save a copy); the Model view; three sample reports; `BI <report> <GO>` opens a report; `drishti.bi.report.*`. The reference is
[REPORTS.md](REPORTS.md) and [RUPAKA.md](../architecture/RUPAKA.md) section 20.3, which also lists what is **not** built (Perspective and live visuals, the map, funnel, histogram, box, bubble, Python and entity visuals,
the relative-date slicer, true totals for measures that do not add up, a Check that runs expected values).

Not built (and not faked): version diff, lineage and impact,
deploying a workspace as a pack bundle (phase 4); streaming BI and Perspective (phase 5); Python in BI (phase 6); export, subscriptions, alerts,
embedding (phase 7); the analyst workbench and Explore (phase 9); *Analyse in BI* from a terminal view; "what changed since your last visit";
AI features (stage 2). The rail's Explore is a placeholder that says so.

## 12. Problems

| Code | HTTP | Meaning |
|---|---|---|
| `DRS-9001` | 404 | no such workspace, item or version, **or you may not know it exists** |
| `DRS-9002` | 403 | you lack a BI power or the workspace role the action needs (the message says which) |
| `DRS-9003` | 422 | a name, kind, body (not YAML, too large), tag, audience or endorsement that is not valid |
| `DRS-9004` | 409 | the state forbids it: name taken, nothing new to publish, already proposed, personal workspace (private, cannot publish or be shared), last admin, workspace not empty, shipped content is read-only |
| `DRS-9005` | 403 | four-eyes: you proposed it, so you may not approve or reject it |
| `DRS-9006` | 403 | BI is switched off on this server (`drishti.bi.enabled`) |

The dataset, query and report codes (`DRS-9010` to `9035`) are in [TROUBLESHOOTING.md](TROUBLESHOOTING.md#error-codes) and [REPORTS.md](REPORTS.md#9-checking-a-report-drs-9030-to-drs-9035).

The menu is missing: you hold no BI power (ask an administrator to grant `bi` on one of your roles in Admin, Roles), the console's
`bi.enabled` is off, or the server could not be asked (the menu then hides rather than failing the page). *Publish* is not offered:
the workspace requires review, or you are not a member with `bi-author`. More in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## 13. Datasets and the query engine (phase 2)

**Datasets** in the rail lists the datasets you may read (shipped files and published ones; the three samples of `config/bi/datasets/` are marked *sample*). A dataset item is the model
reports will sit on; its page (`/bi/d/<id>`) shows the **model** (tables and columns, relationships, measures and their expressions, hierarchies, row rules and which apply to you, masked
columns, and the layers on disk) and a **query preview**: choose fields, measures, one filter, dates and a row limit, **Run the query**, and the grid shows the answer with the layer it came from
(`rollup`, `columns`, `direct` or `live`), the time, whether the cache answered, the dates read, the columns masked for you and any warnings. Authors may tick *Show the SQL*.

* **Who sees what.** Which dataset you can open is the catalogue's decision (audience, workspace). What its data shows you is the dataset's **row rules** and Drishti's **masks**, evaluated on the
  server for you: a desk-head sees their desk's rows, someone without the raw power sees `•••` where the model masks. See [DATASETS.md section 6](DATASETS.md#6-security-row-rules-and-masks).
* **Start a model from the packs** (authors): choose kinds; each becomes a table, the packs' links become relationships (across packs), and relationships are proposed from the data with their match rate. Save the
  draft YAML to your workspace.
* **Publishing** a dataset checks it: an invalid dataset (`DRS-9010` to `9013`) is refused with the first error. Drafts in your own workspace can be queried by you only; shipped and published
  datasets by everyone who may read them.
* **Refresh.** Layers are rebuilt per business date when a data-load signal names the date ([DATA_LOADS.md](DATA_LOADS.md)); an author can also clear and rebuild the current date from the API
  (`POST /api/v1/bi/datasets/{id}/refresh`). Settings: [CONFIGURATION.md](../admin/CONFIGURATION.md#drishtibiquery--the-dataset-query-engine-phase-2).
* **API.** [API_GUIDE.md](API_GUIDE.md#datasets-and-the-query-engine-apiv1bi-phase-2). Problems: DRS-9010 to 9013 (the dataset) and 9020 to 9027 (the query) in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## 14. Reports: the viewer (phase 3)

A **report** is pages of visuals over one dataset ([REPORTS.md](REPORTS.md) is the reference). Open one from the catalogue (a report's page has **Open the report**), with `BI <report> <GO>`, or at
`/bi/r/<id>` (and `/bi/r/<id>/<page>`). Three samples ship, over the three sample datasets: **Trades overview**, **Counterparty risk** and **Cashflow ladder**.

![The Trades overview report](img/bi-reports/viewer-overview.png)

The toolbar holds the **business date** (empty: the latest the data has), **Bookmarks** (saved states of the report), *Click a mark to* **filter the page** or **highlight**, and **Reset**; the filters in force are
chips under the page tabs. Every visual has a header with its title and the buttons that fit it, and a line under it that says how many rows came back, the milliseconds the query took (or "from the cache")
and the layer that answered (`columns`, `rollup`, `direct`, `live`); *masked* is added when the server masked a column for you.

* **Click a mark.** The other visuals filter to it. Here DESK-COMM was clicked: the cards, the line and the table are that desk's, the bar keeps all desks but dims the others, and a chip says what is selected.
  Click it again, press `Esc`, or use the visual's clear button to let go; Ctrl or Shift adds marks. With **highlight** the bar and column charts keep the whole answer and draw the chosen share over it.

  ![A selected bar filters the page](img/bi-reports/viewer-select.png)

* **Drill down and up.** With a mark selected the drill-down button goes one level down the visual's hierarchy (desk to book); the title shows the path; the up button comes back.

  ![Drilled from a desk to its books](img/bi-reports/viewer-drilldown.png)

* **Show the rows** (`r`) replaces the visual with a table of the rows behind it (every column of the table it reads, the selected mark's rows when one is selected); **Show as a table** (`t`) shows the visual's own
  numbers (and is what a screen reader reads). Masked columns read `•••` here too.

  ![The rows behind a bar](img/bi-reports/viewer-rows.png)

* **Drill through.** In **Counterparty risk**, select a counterparty's bar and press *Drill through to Counterparty*: the page about that counterparty opens with the counterparty carried as a chip (it is in the URL, so the
  link can be shared) and a **Back** button. A trade in its table opens in the terminal (*Open in the terminal*, or the link in the key column).

  ![Selecting a counterparty and drilling through](img/bi-reports/viewer-counterparty.png)

  ![The counterparty page](img/bi-reports/viewer-drillthrough.png)

* **The rows you may see are yours.** The report asks the dataset as **you**. A desk head sees the trades of their desk, and the exposure visuals say, with the code, why they cannot be drawn for someone whose row
  rule is on the trades (`DRS-9020`); someone without the raw power sees the counterparty name as `•••`:

  ![The same report for a desk head](img/bi-reports/viewer-counterparty-deskhead.png)

  ![The same report for an analyst: the masked name is one bar](img/bi-reports/viewer-counterparty-analyst.png)

* **Cashflow ladder** drills from the pay year to the quarter, month and day, and lays the legs out as a matrix with totals:

  ![Cashflows drilled from a year to its quarters](img/bi-reports/viewer-ladder.png)

  ![Legs by pay or receive and rate type, with subtotals](img/bi-reports/viewer-matrix.png)

* **Keys.** `Tab` walks the visuals in reading order (top to bottom, left to right); in a chart `Left` and `Right` move between marks and `Enter` selects; `Esc` clears; `t`, `r`, `d`, `u` (table, rows, drill down, up) act
  on the focused visual; `?` lists them.
* **A phone** (390 px) shows one column in reading order, 44 px buttons and no sideways scroll:

  ![A report on a phone](img/bi-reports/viewer-phone-390.png)

The page asks the server for each visual's data **one query at a time, three at once** (the server allows three a person, and the page retries a refusal for busy); a visual that is refused shows the
code and words in its frame with a Retry button while the others carry on. Nothing is drawn from the data except through the same dataset engine, so the limits and the cache of section 13 apply.

## 15. The designer (phase 3)

**Designer** in the rail lists your report drafts and starts a new one on a dataset; a draft opens at `/bi/design/<draft>` (a shipped report opens read-only, with **Save a copy**). The panes are where Power BI
authors expect them: the canvas on the left, then **Filters**, **Visualizations** (with **Build** and **Format**) and **Fields**. The rail steps aside so the designer has the whole width.

![The designer, with a report of two cards, a bar, a line and a slicer](img/bi-reports/design-two-visuals.png)

* **Fields.** The dataset's tables, their columns (a date opens into its parts: year, quarter, month, week, day), its measures and its hierarchies, with a search box. **Drag a field onto the canvas** and the best
  visual for it appears: a measure is a card, a column is a table; **drag another onto that visual** and it improves: a category with a measure becomes a bar, a date with a measure a line, two categories with a
  measure a matrix. Without a mouse, the field's button adds it to the selected visual (or makes a new one). A hierarchy added to a chart makes it drillable.
* **Visualizations.** The 21 types; choosing one changes the selected visual (its fields follow where they fit) or adds a new one. **Build** shows the wells of the selected visual: each takes fields or measures, with
  chips you can remove or reorder and a select that adds without dragging; drill down along a hierarchy and drill through to a page are set here too. **Format**: title and alt text, data labels, stacking, legend,
  axis titles, **colours from the theme** (swatches of the report's palette), number format, conditional colours, data bars, subtotals, gauge range, slicer mode and scope, whether other visuals filter it, most rows,
  sort. With no visual selected, Format is the **page** (title, move, delete, the fields it takes as a drill-through target) and the **report** (title, description, palette, business dates, bookmarks).
* **Filters.** *On all pages*, *On this page*, *On this visual*. **Basic** picks values (type them, or *Pick from the data* to tick them), **Advanced** is a condition (is, is not, less than, between, contains, starts with,
  is empty ...), **Top N** keeps the N values with the highest (or lowest) measure.

  ![A top-5 filter on the bar](img/bi-reports/design-filter.png)

* **The canvas** is the 12-column grid. Drag a visual by its header and drag the corner to resize: both snap to the grid, and what a move would overlap is pushed down. **Keyboard:** focus a visual and `Arrow` moves it a
  cell, `Shift` with an arrow resizes it, `Ctrl+D` duplicates, `Delete` removes, `Enter` goes to its title in Format. A click on the empty canvas lets go of the selection.
* **Real data while you design.** Every visual is drawn from the dataset (as you: your rows, your masks), with the rows and the **query time** under it; **Preview with data** can be turned off to design without queries.
* **YAML.** The tab shows the same draft as text, written the way the sample reports are. Edit either view and the other follows at once; a mistake in the text is reported with its code and path (`DRS-9031`, ...)
  and the canvas keeps the last good draft. A strip of **suggestions** follows the cursor: visual types after `type:`, operators, hierarchies, pages, and the dataset's fields and measures in a well.

  ![The YAML tab](img/bi-reports/design-yaml.png)

* **Check** validates the draft and adds the quality checks: a chart, table, card or image with no title or alt text, a chosen colour that is too pale for the background, visuals that overlap, a visual whose preview
  took over a second (clicking a finding goes to the visual).

  ![Check lists what needs a title](img/bi-reports/design-check.png)

* **Undo and redo** (`Ctrl+Z`, `Ctrl+Y`) walk the last 100 changes; **Save** (`Ctrl+S`) keeps the draft in your workspace; the page warns before you leave with unsaved changes. **Publish** is on the item page
  (review, versions and audience as for any item); a report with errors is refused (`DRS-9030`).

The designer needs the `bi-author` power. Its step-by-step use is [BI_TUTORIAL.md](BI_TUTORIAL.md) chapter 13.

## 16. The Model view (phase 3)

On a dataset's page, **Model view** (authors) opens `/bi/m/<dataset>`: the dataset's **tables as boxes** with their columns (keys marked), its **relationships as lines** with `1` and `*` at the ends (hover a line for its
cardinality and filter direction; a dashed line is inactive), and the YAML below. The diagram and the YAML are **two views of one draft**.

![The cross-pack model as a diagram](img/bi-reports/model-shipped.png)

* **Relate by dragging.** Drag a column onto a column of another table: the form on the right fills in (from the many side, to the one side) with a cardinality guessed from the keys. Check it and press **Add
  relationship**; nothing is written before. The form also works without dragging (choose the tables and columns), and from the keyboard (`Enter` on a column, then `Enter` on another).

  ![Dragging trades.book onto books.id](img/bi-reports/model-relate.png)

* **Edit a relationship** in the list: its cardinality (`many-to-one`, `one-to-many`, `one-to-one`, `many-to-many`), whether it filters one way or both, whether it is active, or remove it. Each change rewrites the
  `relationships:` block of the YAML (in flow style) and the diagram redraws; edit the YAML and the diagram follows.
* **Proposals from the data.** *Look for relationships in the data* asks the server to find columns whose values are ids of another table (profiling, no AI). Each proposal shows the match rate; **Accept** adds it, nothing
  changes until you do. (Links a pack already declares are not proposed: they are in the model from the start.)

  ![A proposal with its match rate](img/bi-reports/model-proposals.png)

* **Layout.** Drag a box by its header; where each table sits is saved in the YAML under `layout:` (`trades: { x: 24, y: 24 }`), which the dataset language ignores.
* **What it changes, and what it leaves.** Only the `relationships:` and `layout:` blocks are rewritten; the rest of the file, comments included, stays as you wrote it (comments inside those two blocks are not kept).
  A shipped dataset opens read-only with **Save a copy**. The diagram never changes a pack: a model's relationships drive BI joins and cross-filtering only.

The Model view calls the dataset checks (`POST /api/v1/bi/datasets/validate`, which now also returns the model it parsed) and, for proposals, `POST /api/v1/bi/model/infer`; both need `bi-author`.
