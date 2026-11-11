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
# Drishti BI and Power BI compared

**Power BI facts checked against Microsoft's documentation on 2026-11-09.** Power BI changes monthly and licensing changes often, so check
[Microsoft's pricing page](https://www.microsoft.com/en-us/power-platform/products/power-bi/pricing) and the linked articles before quoting anything
here. This page quotes no prices. Section 9 lists every source and what could not be verified.

**How to read the status of a Drishti claim.** Every Drishti row says one of these, and nothing else counts as a capability:

| Label | Meaning |
|---|---|
| **Built** | in the product today; checked in the code or a current guide |
| **Built, in part** | the part stated is built; the rest is not |
| **Planned (i2, i3, i4, j...)** | a release in [IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md); **not in the product, and never counted as a lead** |
| **Not built** | not in the product and not in a planned release |

The **Lead today** column looks only at what ships today. Where Drishti plans something Power BI already has, the lead stays with Power BI.

## 1. The verdict

- **Power BI is the better general-purpose BI tool**, and by a wide margin: visuals, DAX, Excel, Teams and Office, Copilot, mobile apps, connectors and community.
- **Drishti is the better tool for a narrower job:** risk and regulated numbers on your own machines, where a total that does not add up must still be right and each figure must be explainable.
- **Its clearest leads are built:** VaR per cell with diversification, a provenance record for every pivot answer, numbers made as each recipient in subscriptions, and refusal rather than a guessed join.
- **Speed is not compared.** No like-for-like benchmark of the two exists, so nothing here says either is faster.
- **Most organisations would run both**, Power BI for enterprise reporting and Drishti for risk. Drishti can feed Power BI through ODBC ([section 6](#6-where-they-differ-by-design)).
- **Architecture, not features:** whether any BI or risk product shares Drishti's kernel and `.dmod` module model is answered in the internal [competitive landscape](COMPETITIVE_LANDSCAPE.md).

## 2. Side by side

| Area | Power BI | Drishti | Drishti status | Lead today |
|---|---|---|---|---|
| **Data connectivity** | Power Query connectors, a [list of several hundred entries](https://learn.microsoft.com/en-us/power-query/connectors/) including SaaS, databases, files, SAP and Snowflake; an on-premises gateway | 19 connector plugins (Delta, Iceberg, JDBC, DuckDB, Kafka, MongoDB, Cassandra, Redis, S3, REST and others) and 3 built in. See the [Data guide](../guides/DATA_GUIDE.md) | Built | **Power BI** |
| **Data preparation** | Power Query (a visual tool and the M language) | Python Transforms and the Notebook; no visual data-prep tool, by decision ([Notebook guide](../guides/NOTEBOOK_GUIDE.md)) | Built (Python); visual tool not built | **Power BI** for analysts |
| **Modelling: relationships** | [One-to-many, one-to-one, many-to-many; single or both cross-filter directions; inactive relationships activated with `USERELATIONSHIP`](https://learn.microsoft.com/en-us/power-bi/transform-model/desktop-relationships-understand) | Facts and dimensions; active relationships joined many-to-one; several facts in a question, each aggregated at its own grain ([section 5.3 of the Reports guide](../guides/REPORTS_GUIDE.md#53-relationships-and-how-a-question-joins)) | Built: both-ways filtering per relationship, many-to-many through a declared bridge table, inactive relationships per measure, an ambiguous path refused at save | **Power BI** |
| **Modelling: calculation language** | [DAX](https://learn.microsoft.com/en-us/dax/dax-overview): measures, calculated columns and tables, filter and row context, `CALCULATE`, user-defined functions, calculation groups | Rachana expressions compiled to SQL: time intelligence, windows, `calc`, `share_of`, `parent_of`, `as_of`, calculation groups, field and what-if parameters ([section 12 of the Tables guide](../guides/TABLES_GUIDE.md#12-measures-in-a-model-filter-context-and-what-is-refused), [Functions](../guides/FUNCTIONS.md)). `calc` with `ignore = table | GROUP`, `having`, per-measure direction and iteration (`sum_by`, `avg_by`, `min_by`, `max_by`); [DAX-to-Rachana cookbook and converter](../guides/RACHANA_REFERENCE.md#dax-to-rachana-cookbook) | Built | **Power BI** on depth; see section 6 |
| **Query engine** | [Import (VertiPaq), DirectQuery, Direct Lake, composite and hybrid tables](https://learn.microsoft.com/en-us/power-bi/connect-data/desktop-directquery-about) | DuckDB over a columns layer per business date, rollups, or the source; one `GROUPING SETS` statement per fact for totals; sketches with a stated error ([section 22](../guides/PIVOT_GUIDE.md#22-bi-on-the-pivot-engine) and [section 23](../guides/PIVOT_GUIDE.md#23-scale-sketches-rollups-progressive-answers-and-the-cluster) of the Pivot guide) | Built | Differ; no benchmark |
| **Clustered compute** | Capacity scales up by SKU; read-only replicas for query load | Pivot views scatter across nodes and merge partial states. BI model questions run on one node | Built, in part ([section 5](#5-clustered-compute-behind-bi)) | Differ |
| **Visuals** | A wide built-in set including ribbon, Gantt (preview), decomposition tree, key influencers, smart narrative, plus [AppSource](https://learn.microsoft.com/en-us/power-bi/visuals/power-bi-visualizations-overview) | About 30 types (charts, map, matrix, KPI, live chart, entity view, Vega-Lite, Python): [section 9.3](../guides/REPORTS_GUIDE.md#93-the-visuals-and-their-wells) | Built | **Power BI** |
| **Custom visuals** | [`.pbiviz` SDK, AppSource, organisational store](https://learn.microsoft.com/en-us/power-bi/developer/visuals/develop-power-bi-visuals) | A Vega-Lite specification or a Python visual; no SDK or marketplace | Built, in part | **Power BI** |
| **Report authoring** | Power BI Desktop (Windows) and the browser | A browser designer with Power BI-style panes, or YAML; a checker that reports before saving ([section 8](../guides/REPORTS_GUIDE.md#8-bi-studio-and-the-report-designer)) | Built | **Power BI** on maturity; Drishti for reports as reviewable text |
| **Interactivity** | Cross-filter and cross-highlight, drill-through across reports, bookmarks, personalised visuals, [what-if parameters up to 1,000 values](https://learn.microsoft.com/en-us/power-bi/transform-model/desktop-what-if) | Cross-filter and highlight, drill-through, bookmarks, tooltip pages, sync slicers, what-if and field parameters ([section 9.10](../guides/REPORTS_GUIDE.md#910-interactions-tooltip-pages-drill-through-and-bookmarks)); a pivot what-if overlay of rows and values ([section 21](../guides/PIVOT_GUIDE.md#21-what-if)) | Built | **Power BI** on report breadth; Drishti on data overlays |
| **Non-additive and risk measures** | Each cell is evaluated from its own filter context, so a ratio or percentile is right at every level when DAX can express it; no built-in VaR engine | Totals from each level's own rows; **VaR, ES and diversification per cell from the risk engines**, never stacked or added ([section 9](../guides/PIVOT_GUIDE.md#9-charts-of-a-selection)); [VaR guide](../guides/VAR_GUIDE.md) | Built | **Drishti** for risk |
| **Explaining a number** | Decomposition tree, key influencers, smart narrative, anomaly detection, forecasting | Per-answer provenance id, cell rows, stable cell references ([section 7](../guides/PIVOT_GUIDE.md#7-provenance-rows-and-cell-references)); "why did it move" ([section 20](../guides/PIVOT_GUIDE.md#20-why-did-it-move-explain-the-difference)); lineage and breakdown of a report number ([section 16.5](../guides/REPORTS_GUIDE.md#165-explain-this-number)). No forecasting or anomaly visual | Built | **Drishti** on provenance; **Power BI** on exploratory AI visuals |
| **Live data** | [Real-time streaming and automatic page refresh](https://learn.microsoft.com/en-us/power-bi/report-server/compare-report-server-service), service only | Live reports, standing queries, boards with live tiles ([section 13](../guides/REPORTS_GUIDE.md#13-live-reports-and-standing-queries)) | Built | Even; not compared in depth |
| **Row-level security** | [DAX rules per role](https://learn.microsoft.com/en-us/power-bi/guidance/rls-guidance), enforced for those with Read permission; a person in several roles sees the union | Row rules on the server per table; **applied as the reader before any join**; a reader named by no rule gets no rows; a visual that cannot apply a rule is refused ([section 5.5](../guides/REPORTS_GUIDE.md#55-row-rules-and-masks-in-practice)). Several rules also union | Built | **Drishti** on fail-closed defaults |
| **Column and object security** | [OLS hides tables and columns](https://learn.microsoft.com/en-us/fabric/security/service-admin-object-level-security), for Viewers only | Masks before aggregation; a masked column cannot be grouped, filtered or sorted on ([section 7 of the Tables guide](../guides/TABLES_GUIDE.md#7-who-sees-what)) | Built | **Drishti** on aggregation behaviour |
| **Sign-on** | Microsoft Entra ID | OpenID Connect ([Admin guide section 5](../guides/ADMIN_GUIDE.md#5-single-sign-on-openid-connect)) | Built (OIDC). SAML, LDAP, Kerberos: **Planned (i4)** | **Power BI** today |
| **Sharing and apps** | Workspaces, apps, org apps, [Teams tabs and chat](https://learn.microsoft.com/en-us/power-bi/collaborate-share/office-integration/service-collaborate-microsoft-teams) | Workspaces, publish, share with a note, catalogue ([section 4](../guides/REPORTS_GUIDE.md#4-home-the-catalogue-workspaces-and-items)); Teams and Slack post a comment and a link ([Operations guide](../guides/OPERATIONS_GUIDE.md#125-chat-bridges-teams-slack-webhooks)) | Built | **Power BI** |
| **Embedding** | [Embed for customers or organisation](https://learn.microsoft.com/en-us/power-bi/developer/embedded/embedded-analytics-power-bi); a capacity for production; public [Publish to web](https://learn.microsoft.com/en-us/power-bi/collaborate-share/service-publish-to-web) | `<drishti-report>` in Shadow DOM, no iframe, each user as themselves ([Embed guide](../guides/EMBED_GUIDE.md)); no anonymous public publishing | Built | Differ |
| **Mobile** | [iOS and Android apps](https://learn.microsoft.com/en-us/power-bi/explore-reports/mobile/mobile-apps-for-mobile-devices) with mobile layouts, alerts, Intune; the Windows app is retired | Responsive web pages with phone and tablet layouts ([section 8.4](../guides/REPORTS_GUIDE.md#84-phone-and-tablet-layouts)); no native app | Built (web only) | **Power BI** |
| **Refresh** | [Scheduled up to 8 a day on shared capacity, 48 on Premium, PPU or Fabric](https://learn.microsoft.com/en-us/power-bi/connect-data/refresh-data); incremental refresh; Direct Lake framing | Schedule, business-day calendar or after a data-load signal, per date ([section 5.6](../guides/REPORTS_GUIDE.md#56-layers-rollups-and-refresh)) | Built | Differ |
| **Subscriptions** | [Email with snapshot or attachment](https://learn.microsoft.com/en-us/power-bi/collaborate-share/end-user-subscribe); with RLS, the image shows **the subscription owner's** data; dynamic subscriptions (preview) | Email on a schedule, **each recipient's own figures**; statements burst one per recipient ([section 18.3](../guides/REPORTS_GUIDE.md#183-subscriptions)) | Built | **Drishti** for per-recipient security |
| **Alerts** | [Dashboard tiles (cards, KPIs, gauges)](https://learn.microsoft.com/en-us/power-bi/create-reports/service-set-data-alerts); report alerts through Fabric Activator; Power Automate | A number on a report, read as you, no flapping ([section 19](../guides/REPORTS_GUIDE.md#19-alerts-on-numbers)) | Built | Even; Power Automate is a Power BI lead |
| **Paginated reports** | Report Builder, service and Report Server | Statements: PDF or Excel, one per recipient ([section 12](../guides/REPORTS_GUIDE.md#12-statements-paginated-reports-page-exact-one-per-reader)) | Built | **Power BI** on maturity |
| **Excel** | [Live PivotTables through Analyze in Excel, connected tables, an Excel add-in](https://learn.microsoft.com/en-us/power-bi/collaborate-share/office-integration/service-analyze-in-excel), PowerPoint export | Export: `.xlsx` with outlines, native charts, a provenance sheet ([Pivot export](../guides/PIVOT_GUIDE.md#10-export), [report export](../guides/REPORTS_GUIDE.md#182-export)); ODBC through a third-party driver the test runs do not cover ([section 7.4](../guides/FLIGHT_REFERENCE.md#74-odbc-excel-power-bi-tableau)). No add-in, no live PivotTable | Built, in part | **Power BI** |
| **AI** | [Copilot](https://learn.microsoft.com/en-us/power-bi/create-reports/copilot-introduction): chat with data, reports, DAX, summaries; paid capacity (F2 or higher, or P1 or higher), not sovereign clouds; some parts in preview | Off by default, provider-neutral: report from words, ask the data, explain, designer help; each answer checked and run as the reader ([section 16](../guides/REPORTS_GUIDE.md#16-ai-in-bi-report-from-words-ask-the-data-explain-this-number)) | Built | **Power BI** on breadth; Drishti on model choice and on-premises control |
| **Governance** | [Lineage and impact analysis](https://learn.microsoft.com/en-us/fabric/governance/lineage), sensitivity labels with Microsoft Purview, tenant settings, activity logs | Versions, four-eyes review, certification, lineage, impact, audit trail ([section 17](../guides/REPORTS_GUIDE.md#17-publishing-and-governance-versions-review-certification-lineage-impact-environments)); no sensitivity-label integration | Built | Differ; **Power BI** on enterprise integration |
| **Deployment pipelines** | [2 to 10 stages](https://learn.microsoft.com/en-us/fabric/cicd/deployment-pipelines/intro-to-deployment-pipelines); Power BI items in preview | Bundles across separate servers, dry run on import ([section 17.7](../guides/REPORTS_GUIDE.md#177-environments-bundles)) | Built | Even |
| **Git** | [Fabric Git integration](https://learn.microsoft.com/en-us/fabric/cicd/git-integration/intro-to-git-integration): Azure DevOps and GitHub, cloud only; reports and semantic models in preview | Reports and models are YAML you can keep in Git yourself; no built-in Git connection | Not built (integration) | **Power BI** |
| **Extensibility** | XMLA endpoint, REST APIs, R and Python visuals, Power Apps and Power Automate visuals, translytical write-back | Custom pivot functions in formula, SQL, Python or Java ([section 19](../guides/PIVOT_GUIDE.md#19-custom-functions)); REST, Arrow Flight, JDBC; connector plugins; Python measures and visuals; [corrections with approvals](../guides/APPROVALS_GUIDE.md) | Built. Signed modules: **Planned (i2)** | Differ |
| **Terminal workflow** | Not described in the Microsoft pages checked | Command words, type-ahead, keys in the grid and designer ([User guide](../guides/USER_GUIDE.md)) | Built | **Drishti** |
| **Licensing and deployment** | Per-user and capacity plans ([pricing](https://www.microsoft.com/en-us/power-platform/products/power-bi/pricing)); cloud service; [Report Server](https://learn.microsoft.com/en-us/power-bi/report-server/compare-report-server-service) on premises with far fewer features | Your own machines or cloud account; all browser code vendored, no CDN; no published price list | Built. Module licensing: **Planned (j)** | Differ |
| **Ecosystem and maturity** | Very large community, training, books, partners, monthly releases | Young product; no public community, certification or partner network | n/a | **Power BI** |

## 3. Where Drishti is ahead today

Each item is built and checked. None of them is a claim about speed.

1. **Risk numbers are real at every level.** A VaR cell comes from the risk engine for that scope; diversification is drawn as its own bar and is the engine's own gap. Power BI can compute a percentile per cell in DAX if the scenario data is in the model, but ships no VaR engine, diversification or backtesting.
2. **A number carries its provenance.** Each pivot answer has a provenance id with the members, data version, filters, statements run and cache hits. Excel exports include a Provenance sheet. The Microsoft pages checked show no per-cell equivalent; that is an absence in those pages, not proof.
3. **Security follows the reader into the output.** Row rules and masks run before joins and aggregation. Subscriptions are made as each recipient, where Power BI's preview image shows the owner's view under RLS.
4. **It refuses rather than guesses.** A field a fact cannot reach, or a measure that cannot apply a row rule, is refused with a code (`DRS-9156`, `DRS-9020`) rather than answered with repeated or blank values.
5. **Exact or approximate is stated.** A sketched `distinct` or `percentile` reports its error in the answer, and you can ask for exact.
6. **Business dates and time travel are in the engine.** As-of questions, business-day calendars and date comparisons are language functions and calendars ([section 7](../guides/REPORTS_GUIDE.md#7-which-date-a-report-reads)).
7. **It runs fully on your own machines, with AI optional and provider-neutral.** Power BI Report Server is the on-premises Microsoft route, and it lacks Q&A, dashboards, data alerts, composite models and more.
8. **The same data is the terminal.** A chart opens the entity behind it, with its links and history.

## 4. Where Drishti falls behind today

These are stated as plainly as the strengths.

1. **Visual breadth.** Fewer built-in types, no custom-visual SDK, no marketplace. No decomposition tree, key influencers, forecasting or anomaly detection visuals.
2. **DAX maturity.** `calc` covers the filter-context patterns and a [converter](../guides/RACHANA_REFERENCE.md#dax-to-rachana-cookbook) handles the common ones, but the body of patterns is smaller than DAX's; the converter names what it cannot convert.
3. **Relationships.** Both-ways filtering and many-to-many through a bridge are built; a table beyond a bridge is brought onto the bridge's near side with a look-up.
4. **Data preparation.** No visual tool. Python is powerful but excludes analysts who do not code.
5. **Connectors.** Tens against hundreds, with no packaged SaaS connectors (Salesforce, Dynamics 365, and so on); the REST plugin is the route to those.
6. **Excel and Office.** Export only; no live PivotTable, no add-in, no PowerPoint export. Teams receives a comment and a link rather than an interactive tab.
7. **Copilot-class AI.** Copilot reaches more surfaces: a cross-item agent, app-scoped answers with verified responses, semantic-model review, DAX help. Drishti's BI AI covers the defined steps of [section 16](../guides/REPORTS_GUIDE.md#16-ai-in-bi-report-from-words-ask-the-data-explain-this-number).
8. **Mobile.** Responsive web only; no App Store apps, Intune device management or Apple Watch.
9. **Proof at scale.** BI has been benchmarked only by its [scale gate](../admin/BENCHMARK.md#19-the-bi-scale-gate), 20,000 rows on one workstation. The [pivot scale run](../admin/BENCHMARK.md#16-the-pivot-engine-at-scale) is a plan still to be measured. Clusters are measured only for VaR, at 200,000 positions.
10. **Ecosystem and maturity.** No public community, certification, training market or partner network, and far less time in production.
11. **Sign-on options, sensitivity labels, Git integration.** OIDC only today; no Purview-style labels; Git is manual.

## 5. Clustered compute behind BI

### What Drishti does today (built)

A Drishti\*net cluster is your own servers acting as one ([Cluster guide](../guides/CLUSTER_GUIDE.md#1-several-servers-as-one-drishtinet)). Each server states what it can serve, and a request goes to a node that can answer it ([section 1.7](../guides/CLUSTER_GUIDE.md#17-where-a-request-goes)). Servers can specialise: BI and reporting, VaR coordinators and shard owners, data-serving, loaders ([section 4.2](../guides/CLUSTER_GUIDE.md#42-the-roles-at-a-glance)).

| Work | How it is spread | Status |
|---|---|---|
| **A pivot view** large enough to need it and mergeable | Every live node reads its share of the files (rendezvous hashing, so caches stay warm) or a row shard, computes partial states per cell key as the same person, and a coordinator merges them in a fixed order. Counts and sketch registers match one node exactly, sums to the last digits. A late node's share is asked of another; a missing share refuses the view rather than answering from the rest ([Pivot guide section 23.4](../guides/PIVOT_GUIDE.md#234-the-cluster-partial-states-from-every-node-merged-in-order)) | Built |
| **A pivot view with a custom or holistic function** | Computed on the node asked | Built |
| **VaR** | Its own sharding with replicas ([section 5](../guides/CLUSTER_GUIDE.md#5-sharding)); the three-node figures equal one server's | Built; [measured at 200,000 positions](../admin/BENCHMARK.md#42-one-server-and-the-three-node-cluster) |
| **A BI report question** | Answered by one node that lists the report and holds its data. Totals are one `GROUPING SETS` statement on that node. **A model is not joined across nodes** | Built, one node per question |
| **A heavy BI run** | Goes to the model's owner by rendezvous; another node adopts it when a lease lapses, with a shared database ([section 5.6](../guides/CLUSTER_GUIDE.md#56-other-aggregations-in-a-cluster)) | Built |
| **More BI capacity** | Add BI servers; the first limit is DuckDB memory and threads ([section 4.6](../guides/CLUSTER_GUIDE.md#46-how-each-role-scales)) | Built |
| **Scattering a BI model question as partial states** | Not built; no row of the plan names it | **Not built** |
| **Lake layout and the very largest VaR scale work** | Design only: [VaR guide section 31](../guides/VAR_GUIDE.md#31-open-items-designed-not-built) | **Not built** |

### How that compares

| | Power BI | Drishti |
|---|---|---|
| **Unit of scale** | A [capacity SKU](https://learn.microsoft.com/en-us/fabric/enterprise/powerbi/service-premium-what-is); larger SKUs give more memory per model, for example 25 GB at F64 and 400 GB at F1024 | Machines you add |
| **In-memory models (Import)** | Bounded by the SKU's memory; a refresh can more than double a model's footprint while it runs | No model-sized memory image; DuckDB reads columnar Parquet per date |
| **Query-time pushdown (DirectQuery)** | Work goes to the source; a [1,000,000-row intermediate limit (raisable on Premium), a 4-minute query timeout](https://learn.microsoft.com/en-us/power-bi/connect-data/desktop-directquery-about) | `mode: direct` reads the source, without engine totals |
| **Lake without import** | [Direct Lake](https://learn.microsoft.com/en-us/fabric/fundamentals/direct-lake-overview) on OneLake Delta tables; guardrails per SKU, 1,500 million rows per table at F64, 24,000 million at F1024; Fabric only, no gateway | Delta or Iceberg read directly, in your own storage; a much less proven path at scale |
| **Concurrency** | [Read-only replicas](https://learn.microsoft.com/en-us/fabric/enterprise/powerbi/service-premium-scale-out), automatic; F64 typically stays at one, larger SKUs may add another | More BI servers, with per-user and overall query limits |
| **Scatter one query over nodes** | Not offered at the model level in the pages checked | Pivot views: yes. BI model questions: no |
| **When overloaded** | [Bursting and smoothing, then delay, then rejection](https://learn.microsoft.com/en-us/fabric/enterprise/throttling); Autoscale on P SKUs, or a bigger F SKU | Admission limits and refusals with a code; you add hardware |
| **Who operates it** | Microsoft | You |

**Reading this fairly.** Drishti's design puts more of the distributed work under your control and shows it in the answer, and Power BI asks far less of your team. Power BI's limits are published and the service is proven at very large deployments. Drishti's pivot scale-out is built but its large-scale runs are not yet measured, and BI model questions do not scatter. **No like-for-like benchmark exists**, so neither speed nor capacity can be compared in numbers. The only BI figures measured are the [scale gate](../admin/BENCHMARK.md#19-the-bi-scale-gate), which measured 20,000 rows on one workstation: 590 ms cold and 18.1 ms warm at the 95th percentile. It is a regression guard and says nothing about Power BI.

## 6. Where they differ by design

These are choices, neither better nor worse in general.

| Question | Power BI | Drishti |
|---|---|---|
| Implicit or explicit context | `CALCULATE` and context transition: very expressive, hard to master | Iteration and context edits are explicit (`avg_by`, `calc`); less expressive, easier to review |
| A field a fact cannot reach | Blank or repeated values, per the model | Refused, with the fields that do work |
| A report's form | `.pbix` or PBIP, edited in Desktop or the service | YAML, reviewable and diffable, with a designer over it |
| Where the numbers are computed | The engine behind the semantic model | Always on the server; the browser only draws |
| Public sharing | Publish to web exists, unauthenticated | Deliberately absent |
| How the two connect | Power BI reads Drishti through an ODBC connector and the Flight SQL driver, without Drishti's own tests covering that driver | A published table in Drishti can feed enterprise BI |

## 7. Which tool fits which job

| Job | Choose |
|---|---|
| Company-wide reporting on sales, finance or operations | **Power BI** |
| Analysts self-serving from SaaS and spreadsheets | **Power BI** |
| Excel-centred teams, Microsoft 365 organisations | **Power BI** |
| Executives on a phone | **Power BI** |
| VaR, ES, exposure and capital by any cut, with diversification | **Drishti** |
| A figure that a regulator or auditor will ask you to explain | **Drishti** |
| No data may leave your machines, including to an AI service | **Drishti**; compare Report Server's feature list |
| A dense keyboard-first desk tool over deep trade documents | **Drishti** |
| A new, large model nobody has tested yet | Prototype on both; neither is proven for your data until you do |

## 8. What is planned

**None of this exists today, and none of it is counted above.** Release names are from [IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md); the plan gives no dates for them. The design is [KERNEL_AND_MODULES.md](../architecture/KERNEL_AND_MODULES.md), itself marked as not built.

| Release | What it brings to BI | Against Power BI |
|---|---|---|
| **i1** Foundations | Data standards, the kernel boundary, faster testing | Internal; no BI feature of its own |
| **i1b** BI depth | **Many-to-many** through a bridge table, **both-ways filtering**, inactive relationships per measure; Rachana **filter-context control**, calculated tables, calculation groups; a DAX-to-Rachana cookbook and converter; a **decomposition tree** that can break down VaR with diversification. Later: a custom-visual SDK, forecast and anomaly overlays, key influencers, smart narrative | Power BI has the relationships, DAX and these visuals today; Drishti plans them. A decomposition of VaR with diversification would be a planned advantage |
| **i2** Modules on one node | Signed `.dmod` modules loaded on one node; a Module SDK | Power BI has AppSource and `.pbiviz` today; a planned advantage only in how domain code is packaged |
| **i3** Modules in a cluster, white label | Placement of modules per node or role; distributions under another brand | Power BI embedding can be branded today; planned advantage for full white-label on your own machines |
| **i4** Console on Spring Boot | Java console; **SAML 2.0, LDAP, Kerberos** alongside OIDC, identity map; **one console over many kernels** | Power BI has Entra single sign-on today; Drishti plans wider options in i4 |
| **j** Trust, RBAC, security, controls | Security on by default, one entitlement service, visibility by role, **data licensing**, **module licensing**, signed modules | Power BI has tenant settings and labels today; planned advantage is data licensing checked with RBAC |
| **k** Risk engines II | SA-CCR, a CCR cube preset, stress testing | Planned; extends Drishti's risk lead |
| **n** Hardening | Adversarial and soak testing | Reduces the maturity gap; does not close it |
| Not scheduled | PostgreSQL wire protocol, so clients could connect as to PostgreSQL | Not a Power BI comparison item; not counted |

Parity with Power BI is a guide rather than a goal: Drishti keeps Power BI-like modelling, but chooses its own design where that conflicts with scale or reuse. A visual data-prep tool is out by decision, and no plan row read for this page names a custom-visual marketplace.

## 9. What was checked, and what could not be

**Drishti.** Checked in the current guides (Reports, Pivot, Tables, Cluster, Data, Notebook, Embed, AI, Admin, Operations, Approvals, Flight), [BENCHMARK.md](../admin/BENCHMARK.md), [IMPLEMENTATION_PLAN.md](../architecture/IMPLEMENTATION_PLAN.md), and in code for the connector plugins, relationship cardinalities (`ModelDef`, `SqlCompiler`) and the pivot partials route (`ClusterRoute`, `PivotScaleController`).

**Power BI: read on 2026-11-09.** The articles linked in the tables above, plus [semantic model scale-out](https://learn.microsoft.com/en-us/fabric/enterprise/powerbi/service-premium-scale-out), [what is Power BI Premium](https://learn.microsoft.com/en-us/fabric/enterprise/powerbi/service-premium-what-is), [calculation groups](https://learn.microsoft.com/en-us/power-bi/transform-model/calculation-groups) and [Power BI Report Server licensing](https://learn.microsoft.com/en-us/power-bi/report-server/get-started).

**Could not be verified, or stated with care:**

- The exact number of Power BI connectors: Microsoft's index gives no count; "several hundred" is a reading of the list.
- A Power BI command-line or terminal workflow: not found in the pages checked, which is not proof of absence. The same holds for a per-cell provenance record.
- Power BI's internal distribution of a single query across nodes: Microsoft does not document it at this level, so the comparison speaks only of what is published.
- Prices: not quoted; the plans listed are on Microsoft's pricing page.
- Certification and endorsement in Power BI: the Microsoft article could not be opened on the date checked, so this page makes no specific claim about it.
- Any speed or capacity comparison: no like-for-like benchmark exists.
