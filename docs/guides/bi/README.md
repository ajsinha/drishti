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
# Business intelligence

Business intelligence (BI) in Drishti, called **Rūpaka** (type `BI <GO>` or choose the bar-chart icon in the top bar), is one engine from a table to a
dashboard: slice any table in the **pivot**, describe it once as a **table** and a **model**, make a **report** that is published, scheduled, put on a
wall, sent as a statement or watched by an alert, and go further in the **Notebook** with SQL and Python. Every number is computed on the server, as the
person looking: their row rules and masks apply to every grid, visual, export, mail, alert and embedded report. The browser only draws.

BI is a section of eleven pages, not one long guide. Each page is a guide followed by its tutorial, so a topic is read, then done, in one place. This
index is the map.

## The pages and where each tutorial is

| Page | What it covers | Its tutorial |
|---|---|---|
| [Pivot](PIVOT.md) | The one grid for slicing and totalling any table: the View picker, nested rows, top N with a correct Others, totals made from their own members, provenance, custom functions, why did it move, what-if, BI on the pivot engine | Part A, the pivot engine; Part B, functions, why did it move, what-if |
| [Pivot at scale and the risk cubes](PIVOT_SCALE_AND_CUBES.md) | Sketches, rollups, progressive answers, the cluster, the screen's switches, live pivots, cell comments, presets and the market-risk (MR) cube | the market-risk cube |
| [Tables and models](TABLES_AND_MODELS.md) | Virtual tables and semantic models over the data: definitions, expressions, materialisation, who sees what, checks, lineage and the editors | virtual tables and models |
| [Reports and the designer](REPORTS.md) | What a report is, the BI menu, the four powers, the catalogue and workspaces, the viewer, which date a report reads, BI Studio and the designer | Part A, your first BI report |
| [The report file, formatting and statements](REPORT_FILE_FORMAT_STATEMENTS.md) | The report YAML, visuals and their wells, filters, slicers, the Format pane, conditional formatting, themes, field parameters, paginated statements | Part C, statements and field parameters |
| [Models and datasets for reports](MODELS_AND_DATASETS_FOR_REPORTS.md) | What a report reads: the Models page, relationships and how a question joins, measures, row rules and masks, live tables, expected values, worked examples | Part B, several datasets in one report |
| [Live reports, boards and background runs](LIVE_REPORTS_AND_BOARDS.md) | Live reports and standing queries, boards of live tiles, kiosk mode, background runs for long questions | Part F, live BI and boards |
| [Distribution, subscriptions and alerts](DISTRIBUTION_AND_ALERTS.md) | The Reports page, scheduled searches, export, subscriptions, webhooks and texts, alerts on numbers, embedding | Parts D and E, BI that runs itself; scheduled searches and subscriptions |
| [Governing BI and AI in BI](GOVERNANCE_AND_AI.md) | Report from words, ask the data, explain this number; versions, review, certification, lineage, impact, environments | Part G, govern BI |
| [The Notebook and Python](NOTEBOOK_AND_PYTHON.md) | SQL and Python cells, Python measures and visuals, Transforms, the sandbox, limits, turning Python on | the Notebook and Python |
| [Groupings and analytics](GROUPINGS_AND_ANALYTICS.md) | LEIG, LEIC, SIC, ECG, CORR, EQB, PXH and the USEQ ratios, computed instead of stored: the data contract and how they are computed | groupings and analytics, computed |

The section numbers of the reports topic (1 to 22) run across the five pages from Reports to Governing BI, so a reference such as "section 14" names
the same section wherever it is read; the pivot's sections (1 to 29) run across its two pages.

## Where to start

| You want to | Read |
|---|---|
| **Learn by doing** | [Reports, Part A](REPORTS.md#part-a--your-first-bi-report) (a first report: map, bar, slicer, publish, filters, several models, relative dates, a tablet); then [BI that runs itself](DISTRIBUTION_AND_ALERTS.md#part-d--bi-that-runs-itself) (refresh, subscriptions, alerts, export, expected values), [live reports and boards](LIVE_REPORTS_AND_BOARDS.md#part-f--live-bi-and-boards), [governance](GOVERNANCE_AND_AI.md#part-g--govern-bi), [several facts in one report](MODELS_AND_DATASETS_FOR_REPORTS.md#part-b--several-datasets-in-one-report), [statements and field parameters](REPORT_FILE_FORMAT_STATEMENTS.md#part-c--statements-and-field-parameters), [the Notebook](NOTEBOOK_AND_PYTHON.md), [tables and models](TABLES_AND_MODELS.md), [AI](../../tutorials/10-ai-assistant.md), [embedding](../../tutorials/20-embed-drishti.md) |
| Slice a table now | [Pivot](PIVOT.md) |
| Try each step with real answers, four people and row rules at work | [Models and datasets for reports, worked examples](MODELS_AND_DATASETS_FOR_REPORTS.md#19-worked-examples-the-shipped-models-row-rules-and-masks) |
| Find your way round the pages, powers and workspaces | [Reports, sections 2 to 4](REPORTS.md#2-getting-in-the-bi-menu-the-rail-and-bi-go) |
| Write or read a table or a model | [Tables and models](TABLES_AND_MODELS.md); what reports need of them: [section 5](MODELS_AND_DATASETS_FOR_REPORTS.md#1-models-and-datasets-what-a-report-reads) |
| Design a report | [BI Studio](REPORTS.md#7-bi-studio-and-the-report-designer); the file: [section 9](REPORT_FILE_FORMAT_STATEMENTS.md#1-the-report-file-pages-visuals-filters-and-slicers) |
| Put a wall of tiles on a screen | [Boards](LIVE_REPORTS_AND_BOARDS.md#2-boards-report-pages-of-live-tiles) |
| Follow a long question that runs in the background | [Background runs](LIVE_REPORTS_AND_BOARDS.md#3-background-runs-long-bi-questions) |
| Publish, review, certify, trace lineage, move content between servers | [Publishing and governance](GOVERNANCE_AND_AI.md#2-publishing-and-governance-versions-review-certification-lineage-impact-environments) |
| Export, subscribe, schedule, alert | [Distribution](DISTRIBUTION_AND_ALERTS.md#1-distribution-the-reports-page-export-and-subscriptions), [alerts](DISTRIBUTION_AND_ALERTS.md#2-alerts-on-numbers) |
| Show a report in another web application | [Embedding](DISTRIBUTION_AND_ALERTS.md#3-embedding-a-report-in-another-web-application), [EMBED_GUIDE.md](../EMBED_GUIDE.md) |
| Compute a measure or a visual in Python | [The Notebook and Python](NOTEBOOK_AND_PYTHON.md) |
| Open a group, a sector or a correlation computed from the data | [Groupings and analytics](GROUPINGS_AND_ANALYTICS.md) |
| Grant the powers, set the limits | [ADMIN_GUIDE.md](../ADMIN_GUIDE.md#34-bi-powers-rupaka), [CONFIGURATION.md](../../admin/CONFIGURATION.md) |
| Call it from a program or the command line | [API_REFERENCE.md](../API_REFERENCE.md), [CLI_REFERENCE.md](../CLI_REFERENCE.md#bi-workspaces-and-the-catalogue-rupaka) |
| Compare this with Power BI, honestly: leads, gaps, what is planned | [Drishti BI and Power BI compared](../../strategy/BI_AND_POWER_BI.md) |
| Fix a problem | [Reports, settings, limits and problem codes](REPORTS.md#9-settings-limits-and-problem-codes), [OPERATIONS_GUIDE.md](../OPERATIONS_GUIDE.md#21-troubleshooting) |

A good first hour: read [Reports, sections 2 to 4](REPORTS.md#2-getting-in-the-bi-menu-the-rail-and-bi-go), set up the stack of the
[worked examples](MODELS_AND_DATASETS_FOR_REPORTS.md#19-worked-examples-the-shipped-models-row-rules-and-masks), then do the reports tutorial, Part A.

## A short tour

1. **Slice.** Open `PIVOT trade <GO>`: the one grid for any table. Drag fields, open a row, take the top 10 with a correct **Others**; a total is never a sum of
   what is shown when it cannot be ([Pivot](PIVOT.md)). Save the view and share it.
2. **Describe.** A **table** says which documents and paths make rows; a **model** joins tables and owns the measures ("GDP per capita", "VaR") once, for
   every report ([Tables and models](TABLES_AND_MODELS.md), [what a report reads](MODELS_AND_DATASETS_FOR_REPORTS.md)).
3. **Report.** Make a report in **BI Studio**: cards, a map you can drill, a bar, a slicer on a 12-column grid, then publish it to a workspace and the
   catalogue ([Reports](REPORTS.md), [the report file](REPORT_FILE_FORMAT_STATEMENTS.md)).
4. **Keep it alive.** Let it refresh, subscribe a colleague, alert on a number, export to PDF or Excel, embed it, put it on a wall
   ([Distribution](DISTRIBUTION_AND_ALERTS.md), [Live](LIVE_REPORTS_AND_BOARDS.md)).
5. **Govern it.** History, review, certification, lineage, what breaks if a field goes, moving content between servers
   ([Governing BI](GOVERNANCE_AND_AI.md)).
6. **Go further in code.** SQL and Python in the Notebook, Python measures and visuals, Transforms ([Notebook](NOTEBOOK_AND_PYTHON.md)); and the groupings and
   analytics Drishti computes when you open them ([Groupings](GROUPINGS_AND_ANALYTICS.md)).

## Conventions of these pages

- Each page opens with its **Contents**, then **Guide** (what you see and what you do, then the YAML, the API and the settings), then **Tutorial** (steps that
  each end with what you should see). A tutorial step shows the UI, the command line where there is one, and the YAML.
- The tutorial of a topic that spans pages (Pivot, Reports) keeps its set-up under **Before you start** on its first page; the others link to it.
- One home per fact: a page links to the page that owns a fact rather than repeating it.
