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
# Tutorial 6 · Design a screen

A screen in Drishti is a **Sutra**: a small YAML file that says which entities it applies to and what to show. You design it in the **Build workbench**, with a live preview over your own sample
documents. In this tutorial you design a desk screen with a strip of key figures, **tabs that each hold two panels** (a currency's totals and its trades), a **slider** that narrows the trades by size,
and the text behind **About this page**. You also see the column filters every table has, and check the Sutra from the command line.

## Time to complete

About 40 minutes.

## Before you start

- [Tutorial 1](01-first-15-minutes.md) done; the server started with `DRISHTI_STUDIO_SAVE=true`; signed in. Anyone may design; saving a Sutra for everyone needs an author and a reviewer, which this tutorial does not do.
- The data: three desk documents in `docs/tutorials/data/desks/` (`desk-rates.json`, `desk-fx.json`, `desk-credit.json`: each a desk with its currencies and their trades), and the Sutra itself,
  `docs/tutorials/data/desk-book.sutra.yaml`. The workbench guide ([SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md)) and the earlier [workbench tutorial](../guides/BUILD_WORKBENCH_TUTORIAL.md) go further; this one is the shortest route to a finished screen.

## What you will learn

- how to start a design from documents, and what the auto-design gives you;
- the parts of a Sutra: `match`, `title`, `strip`, `controls`, `panels`;
- a `tabs` panel with several panels inside each tab, and a **slider** that filters what is inside;
- column filters and phone width;
- the *About this page* text and glossary, and where it is written;
- `sutra lint` and `sutra preview` from the command line.

## Step 1 · Bring your documents

Choose **Build**, then **New screen**. Choose the three desk files, name the design `Desk book` and give it the kind `desk`.

![New screen: three files ready, a name and a kind](img/t06/01-new-screen.jpg)

You should see `3 files ready.`, the name and the kind filled in, and the start choices (auto-design on). Press **Create**.

![The first draft: a strip, a details panel and a links panel drafted from the documents](img/t06/02-first-draft.jpg)

You should see the workbench: your files as **Samples** on the left (switch between them with the arrows at the bottom), the **Design** canvas in the middle with a first draft the auto-designer made
(a title, a strip of the document's figures, a *Details* panel, a *Currencies* table), and the **Inspector** on the right. The first draft is a start: its figures show as raw values (`Limit use 1`), and
the currencies are a plain table. Next you replace it with a designed one.

## Step 2 · Read the Sutra, and replace it

Open the **YAML** tab. Select everything, and paste the contents of `docs/tutorials/data/desk-book.sutra.yaml`.

![The YAML tab: the desk-book Sutra, with line numbers and highlighting](img/t06/03-yaml.jpg)

You should see the Sutra in the editor. Read it from the top:

- `match: { kind: desk }`: it applies to every document of the kind `desk`.
- `title`: a pill (`Desk · ${$.region}`), the id (`$.deskId`) and the desk's name.
- `strip`: five key figures, each a label, a path into the document (`$.mtm`) and a format (`signed0`, `pct0`); `tone: sign` colours a number by its sign.
- `controls`: one control, a slider called `minNotional`.
- `panels`: one `tabs` panel; `each: $.currencies` makes one tab for each currency, named by `tabTitle: "@.code"`, and `panels:` lists what each tab holds. Inside a tab `@` is that currency.
  The second panel's `where: "@.notional >= ctl.minNotional"` keeps the trades at least as big as the slider says.

Click the **Design** tab.

![The designed screen: the title, the strip, and the By currency panel with tabs EUR, GBP and USD, each holding Totals and Trades](img/t06/04-tabs.jpg)

You should see `Desk · EMEA` and `DESK-RATES`, the strip, and a panel **By currency** with three tabs, `EUR`, `GBP`, `USD`. The selected tab holds two panels: **Totals** (currency, net MTM
coloured by sign, notional) and **Trades**, a table with a **Filter rows** box and paging. Click another tab, and switch sample with the arrow at the bottom: the tabs follow the data, however many currencies a desk has.
The panel is narrower than the canvas: **drag its edges** or press `Shift+→` on it to resize, `Alt+arrows` to move it.

## Step 3 · The slider

Open the **Controls** tab. The slider you wrote is there, as a card you can edit: its label, **Min** `0`, **Max** `250000000`, **Step** `25000000`, whether it has two handles (a range) and where it sits
(`strip`, `header` or `side`).

![The Controls tab: the minNotional slider, and under Try them on the sample the slider over the panel that reads it](img/t06/05-controls-tab.jpg)

You should see the card and `The controls read without problems.` Scroll to **Try them on the sample** and drag the slider (or focus it and press `→` for one step).

![The slider at 100000000: the Trades table shows only the three trades of 100 million or more](img/t06/06-slider-moved.jpg)

You should see the label `Notional at least`, the handle and its value, and the trades table shrink: with the handle at 100,000,000 only 3 of the 9 EUR trades are left. A reader on the live screen gets the
same slider, and what they choose is in the page's address, so a link reproduces it. Keys: `←` `→` one step, `PageUp` `PageDown` ten, `Home` `End` the ends; on a phone the handle is 44 pixels. A slider
with `range: true` has two handles and gives `from..to`.

## Step 4 · Column filters, and the phone

Every table in Drishti has a funnel on each column heading, like a spreadsheet. The workbench preview is a sketch, so look at one on a live view: open `TRD MX-20000001`, find the cashflows table and click
the funnel in the **Amount** heading.

![The Amount column's menu: sort, the condition Greater than 5m, a search box and the values with tick boxes](img/t06/07-column-filter.jpg)

You should see a menu with two sort buttons, a **condition** (equals, greater than, between, top 10 ...) with its value box, a search box over the column's values and the list of values with tick boxes, and **OK**, **Cancel** and **Clear**. Choose *Greater than*, type `5m` and press **OK**: only the rows above 5 million stay, and the funnel on the heading is filled in. A tick-box list works for text, a date condition for dates. [USER_GUIDE.md › Filter a table like Excel](../guides/USER_GUIDE.md#filter-a-table-like-excel) has them all.

![The same table at 390 px: one column, funnels still there](img/t06/08-phone.jpg)

You should see the same table on a phone, one column, with 44-pixel targets. In the workbench use the **Phone** button at the bottom to see your own screen that way before you propose it.

## Step 5 · Check it

The Problems tab lists everything wrong; the Tests tab checks every sample.

![The Problems tab: no problems, the YAML reads, the operations applied and the bound fields are in the samples](img/t06/10-problems.jpg)

You should see `No problems: the YAML reads, the operations applied and the bound fields are in the samples.` Introduce a mistake (change `kind: tabs` to `kind: tab`) and the tab names the line and the
reason, with the code (`DRS-2021 unknown panel kind`). Undo with `Ctrl+Z`.

The command line runs the same checks, which is what a CI job would do. Put the Sutra and one document side by side and lint it:

```bash
mkdir -p desk-test && cp docs/tutorials/data/desk-book.sutra.yaml desk-test/
cp docs/tutorials/data/desks/desk-rates.json desk-test/desk-book.json
python3 tools/drishti.py sutra lint desk-test/desk-book.sutra.yaml
```

```text
$ python3 tools/drishti.py sutra lint desk-book.sutra.yaml
ok      desk-book.sutra.yaml
desk-book.sutra.yaml:1 warning DRS-2047 field 'mtm' is shown but has no glossary entry (kinds.desk.glossary in config/about.yaml, or a vocabulary entry)
...
```

You should see `ok desk-book.sutra.yaml` and a list of warnings, `DRS-2047 field 'mtm' is shown but has no glossary entry`: the screen works, but nothing yet says what its fields mean. (Lint is quiet by
default about help text; `--strict` makes these warnings fail the run.)

```text
$ python3 tools/drishti.py sutra lint --strict desk-book.sutra.yaml
ok      desk-book.sutra.yaml
desk-book.sutra.yaml:1 warning DRS-2047 field 'mtm' is shown but has no glossary entry (kinds.desk.glossary in config/about.yaml, or a vocabulary entry)
desk-book.sutra.yaml:1 warning DRS-2047 field 'var99' is shown but has no glossary entry (kinds.desk.glossary in config/about.yaml, or a vocabulary entry)
desk-book.sutra.yaml:1 warning DRS-2047 field 'limitUse' is shown but has no glossary entry (kinds.desk.glossary in config/about.yaml, or a vocabulary entry)
desk-book.sutra.yaml:1 warning DRS-2047 field 'openTrades' is shown but has no glossary entry (kinds.desk.glossary in config/about.yaml, or a vocabulary entry)
desk-book.sutra.yaml:1 warning DRS-2047 field 'headOfDesk' is shown but has no glossary entry (kinds.desk.glossary in config/about.yaml, or a vocabulary entry)
...
[exit 1]
```

`preview` renders every sample and reports panels in error:

```text
$ python3 tools/drishti.py sutra preview desk-book.sutra.yaml
desk-book / desk-book.json: 1 panels, 0 in error
```

You should see `1 panels, 0 in error`.

## Step 6 · About this page

The warnings point to the *About this page* text: press `?` on any view.

![The About drawer of a trade: what the page is, each panel, a glossary of the fields and where the data came from](img/t06/11-about-drawer.jpg)

You should see a drawer with the page's text, a line for each panel, a glossary (term, meaning, unit) of the fields you can see, where the data came from and why this layout was chosen. Your Sutra's
`description` and each panel's `description` fill it; the glossary and the page text are the pack's `config/about.yaml`. In the workbench, open the **About** tab and press **Starter**.

![The About tab: the skeleton for the kind, the glossary entries to write, and the card the drawer will show](img/t06/09-about-tab.jpg)

You should see a skeleton for the kind, the panel texts and a glossary entry for each field the preview shows, marked as still to write (`What mtm means.`), and the **About card** under it. The line under the
button reads `13 fields; 13 explained (help coverage 13/13)`. Write real sentences in the box and the card updates as you type; the lint warnings vanish as entries appear. The text is saved with the design and
travels with the pack fragment you export; it goes live when an administrator deploys the pack ([SCREEN_DESIGNER.md › The About tab](../guides/SCREEN_DESIGNER.md#the-about-tab)).

## Step 7 · What you have, and what comes next

The design is yours (see **My designs** under Build). To make it live for everyone: **Submit for review**, a reviewer with the approve power reads the diff and the evidence, and approves; or export it as a pack
fragment (**Ship**) and put it in a pack. [BUILD_WORKBENCH_TUTORIAL.md](../guides/BUILD_WORKBENCH_TUTORIAL.md) walks through proposing and approving as two people.

You should see, under **Build → My designs**, `Desk book` with the status *draft* and your edits listed.

## Next steps

- [Tutorial 7 · Load data daily](07-load-data-daily.md) keeps the screens' data fresh.
- [SCREEN_DESIGNER.md](../guides/SCREEN_DESIGNER.md) is the workbench reference; [RACHANA_REFERENCE.md](../guides/RACHANA_REFERENCE.md) lists every option of every panel and control; [PANEL_KINDS.md](../guides/PANEL_KINDS.md)
  shows each of the 21 panel kinds with a picture; [SUTRA_DEVELOPER_GUIDE.md](../guides/SUTRA_DEVELOPER_GUIDE.md) is the language guide.

## If something goes wrong

| You see | Meaning |
|---|---|
| a problem naming a line | the Problems tab says which line and why; click it to put the cursor there |
| `DRS-2023` | an option on a panel that does not take it (`panels:` is only for `tabs`, `body` likewise) |
| the Trades table is empty | the slider is above every trade, or the `where` reads a control that has no value |
| the workbench says the design changed in another tab | reload it: the other tab's edit wins, nothing is lost (Versions) |
