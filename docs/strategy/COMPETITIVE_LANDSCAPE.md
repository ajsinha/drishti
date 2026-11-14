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
# Competitive landscape: kernel and modules

> **Internal: not for customers.** This page names other vendors' products and gives our reading of their public material. Do not
> paste it into a deck, a proposal or an e-mail to a prospect.

**Status: analysis** (internal; owner: the product owner; every web source read on **2026-11-11**). It answers one question: **does any
commercial product have Drishti's micro-kernel plus module (plug-in) architecture for data, BI and risk, and how does Drishti compare?**
Vendor-by-vendor capability (VaR, FRTB, XVA, pricing, deal positioning) is in [COMPETITIVE_ANALYSIS.md](COMPETITIVE_ANALYSIS.md); Power BI
area by area is in [BI_AND_POWER_BI.md](BI_AND_POWER_BI.md). This page covers only the **architecture**: how each product is extended,
packaged and deployed. Every vendor statement is the vendor's own (we have tested none of these products); a statement we could not
source says **not verified**.

## Contents

1. [The answer in short](#1-the-answer-in-short)
2. [Summary table](#2-summary-table)
3. [Per product](#3-per-product)
4. [Where Drishti is genuinely different](#4-where-drishti-is-genuinely-different)
5. [Where Drishti is behind](#5-where-drishti-is-behind)
6. [What to borrow (ideas only)](#6-what-to-borrow-ideas-only)
7. [Sources](#7-sources)

## 1. The answer in short

- **No product found has the same combination.** Risk vendors are modular in the commercial sense (licensable functional modules of one
  vendor platform); plug-in platforms with real package formats (Snowflake, Salesforce, Grafana, Splunk, Elastic) carry no risk engine.
  We found no risk or BI product that documents a domain-free kernel, a separately built and loaded industry package, and a
  white-label distribution file together.
- **ActiveViam Atoti is the closest.** An aggregation engine (Atoti Server), risk solutions built on it (Market Risk, FRTB) and add-on
  modules (Limits, Sign-Off), with Java JAR extensions that register measure plugins and web services. But the solutions are delivered as
  **source and starter projects built with Maven** into one application, and extension is licence-gated; we found no hot-loadable,
  signed package with placement across a cluster.
- **Drishti's difference is real but young.** It is a design largely built (kernel API, the `.dmod` loader, first-party check, placement,
  distributions), with one industry module of substance (banking), no third-party module author, no marketplace, no certification and no
  reference client.

## 2. Summary table

"Kernel / module model" means: a core that knows no industry, extended by **packages built apart from it** that install, upgrade and
uninstall as units. *Partial* means packages exist but extend a product that is itself domain-specific, or are compiled in rather than
loaded.

| Product | What it is | Closest overlap with Drishti | Kernel / module model | Deployment | Licensing model |
|---|---|---|---|---|---|
| **Drishti** | self-hosted data terminal, BI and on-demand risk | (itself) | **Yes**: domain-free kernel, `.dmod` modules, distribution files | on-premises, single node or cluster | proprietary; no published price |
| **ActiveViam Atoti** | in-memory aggregation engine with risk solutions | risk aggregation cube, Python and Java extension | **Partial**: engine + solutions + add-on modules, delivered as source; JAR extensions | on-premises or cloud (customer-run) [A1] | free Community Edition with limits; commercial licence for the rest [A2][A3] |
| **Murex MX.3** | integrated trading, risk and processing platform | enterprise risk and regulatory suite | **Partial**: "deployed in full or in a modular way" (vendor modules) [M1] | on-premises, IaaS, PaaS, SaaS (MXSaaS) [M2] | enterprise licence or SaaS; not published |
| **Nasdaq Calypso** | trading, treasury, risk and collateral platform | market and credit risk modules | **Partial**: "modular architecture", stepwise adoption [C1][C2] | on-premises or managed on AWS (CapCloud) [C3] | enterprise; not published |
| **FIS Enterprise Risk Suite** (formerly Adaptiv) | market and counterparty risk, SA-CCR, FRTB, SIMM | VaR, exposure, limits | **No** public module format; containerised microservices [F1] | on-premises, FIS-hosted, public cloud; continuous delivery on AWS [F1][F2] | enterprise; not published |
| **Numerix Oneview** | pricing, XVA, market and counterparty risk | risk analytics | **Partial**: sold per module (XVA, Valuation, Market Risk, Trading) [N1] | SaaS on AWS; on-premises and private cloud also listed [N2] | SaaS per module (an AWS Marketplace listing shows a yearly price per module) [N1] |
| **MSCI RiskMetrics RiskManager** | hosted enterprise VaR, stress and factor risk | VaR and stress for a portfolio | **No** | hosted by MSCI; web, reports, APIs [R1] | subscription; not published |
| **Bloomberg MARS** | multi-asset valuation and risk on Bloomberg's pricing library | VaR, sensitivities, XVA | **No**: modules are functions of one service | Terminal, MARS API, delivery to a private cloud [B1][B2] | Bloomberg subscription; not published |
| **Snowflake Native Apps** | apps packaged and installed inside a customer's Snowflake account | the package model | **Yes** (on a data platform): application package, manifest, setup script, versions and patches [S1] | Snowflake only | provider sets price; Marketplace or private listing [S1] |
| **Salesforce packages** (2GP managed, unlocked) | metadata and code packages for a Salesforce org | namespaces, dependencies, upgrades | **Yes** (on a CRM platform) [SF1][SF2] | Salesforce only | not checked here |
| **Splunk apps and add-ons** | packaged searches, dashboards, inputs | packaged content plus vetting | **Yes**: manifest, packaging toolkit, AppInspect vetting [SP1] | Splunk Enterprise or Cloud (Cloud needs vetting) [SP1] | not checked here |
| **Elastic plugins** | JVM plugins for Elasticsearch | Java plugins on a cluster | **Yes**, tightly bound: exact version match, install on every node [E1][E2] | self-managed clusters [E1] | not checked here |
| **Grafana plugins** | data source, panel and app plugins | app plugin bundles data sources, visuals and dashboards | **Yes**: three plugin types, five signature levels [G1] | self-hosted or Grafana Cloud (private plugins cannot be installed in Cloud) [G1] | by signature level: Grafana, Enterprise, Community, Commercial, Private [G1] |
| **Apache Superset** | open-source BI | BI on the customer's own database | **Partial**: an extension architecture under development [SU1] | self-hosted | open source (Apache Software Foundation) |
| **Kubernetes operators** | software extensions that manage an application through custom resources | placement, upgrade, leader election | not a product; a pattern [K1] | any Kubernetes cluster | n/a |
| **Power BI** | Microsoft BI | BI and pivot | **Partial**: custom visuals and connectors on a closed core | see [BI_AND_POWER_BI.md](BI_AND_POWER_BI.md) | see that page |
| **Tableau** | BI | BI and dashboards | **Partial**: dashboard and viz extensions (JavaScript, a manifest) [T1] | not checked here | not checked here |
| **Qlik Sense** | BI with an associative engine | BI | **Partial**: visualization extensions (a zip with `.qext`); nebula.js for new work [Q1][Q2] | Qlik Cloud and client-managed [Q2] | not checked here |
| **Looker** | BI on a semantic model (LookML) | the model as code, packaged content | **Partial**: Marketplace blocks (LookML models), applications on the extension framework [L1] | Google Cloud [L1] | not checked here |

## 3. Per product

### 3.1 Risk aggregation: ActiveViam Atoti

- **Architecture.** Atoti Server (Java) is the aggregation engine; the Python SDK starts a session over it. Server extensions are **Java
  JARs** passed to the session; they register **measure plugins** (called from Python with `plugin_measure()`) and **Spring web
  services**; UI extensions are JavaScript or TypeScript, and branding (logo, title, favicon) is a session setting [A1].
- **Solutions and modules.** Atoti Market Risk uses a separate **Limits** module (its own limits cube inside the Atoti application) and
  the **Sign-Off** approvals module [A4][A5]. The Market Risk distribution ships **UI source code, a UI build and source files to build the
  module**; the Limits module is downloaded as **source with a starter project and parent pom**, built with `mvn clean install` [A4][A6].
  So the unit of extension is a **Maven project compiled into the customer's application**, not a package dropped into a running server.
- **Licensing.** A free Community Edition exists; the vendor's table and its EULA disagree on its user limit (two users on the site; one
  builder and one viewer, internal use and no commercial use in the EULA of May 2025) [A2][A3]. Extending the app is **not in the Community
  Edition** and needs a licence [A1]. Paid prices are not published.
- **Against Drishti.** Atoti is mature, certified for FRTB and has reference clients ([COMPETITIVE_ANALYSIS.md](COMPETITIVE_ANALYSIS.md#331-activeviam-atoti));
  Drishti differs in shape: domain-free kernel, modules loaded and removed at run time, one distribution file per product, open lake storage.

### 3.2 Trading and risk platforms: Murex, Calypso, FIS, Numerix

All four describe themselves as **modular**, meaning functional modules of one vendor platform licensed and deployed separately:

- **Murex MX.3**: tiered and service-oriented; "deployed in full or in a modular way"; Kubernetes and containers for market risk and
  reporting; IaaS to SaaS [M1][M2]. A Microsoft reference architecture states narrower Azure support (Windows virtual machines, no PaaS);
  it is older than Murex's own page, so the current position is **not verified** [M3].
- **Nasdaq Calypso**: "cloud-ready, modular architecture"; the AWS Marketplace listing says the modular design allows stepwise adoption;
  CapCloud is a Nasdaq-managed service on AWS [C1][C2][C3].
- **FIS Enterprise Risk Suite**: containerised microservices; on-premises, FIS-hosted or public cloud; launched on AWS in May 2026 with
  continuous delivery, clients always on the latest version [F1][F2].
- **Numerix Oneview**: cloud-native, delivered as SaaS on AWS; the AWS Marketplace listing sells XVA, Valuation, Market Risk and Trading as
  separate yearly subscriptions [N1][N2].

**None documents a public package format through which a third party, or the client, builds a module the platform loads.** (Each has
customisation and APIs; their internal module boundaries are **not verified** from public material.)

### 3.3 Hosted risk services: MSCI RiskManager, Bloomberg MARS

- **MSCI RiskMetrics RiskManager**: a hosted service on the RiskMetrics engine (VaR, factor, stress, liquidity, counterparty), with
  MSCI's data; results through a web application, reports and APIs [R1].
- **Bloomberg MARS**: Front Office, Valuations, Market Risk and other functions on Bloomberg's pricing library; reached through the
  Terminal or the MARS API (built on Bloomberg's server API and B-PIPE), also through BQL and BQNT or delivered to a private cloud [B1][B2].

Both are **services**, not platforms a client extends; the module question does not apply. They supply the data and the pricing Drishti
does not have.

### 3.4 Plug-in platforms: the module model elsewhere

| Platform | Package | Install and upgrade | Trust | Lesson for Drishti |
|---|---|---|---|---|
| **Snowflake Native Apps** [S1] | application package: data, logic, **manifest**, **setup script** | setup script runs on install and upgrade; **versions and patches** rolled out gradually; Marketplace or private listing | the consumer **grants privileges** to the app after install | the consumer grants what a module may touch; staged rollout |
| **Salesforce 2GP and unlocked** [SF1][SF2] | metadata and code with an optional **namespace** | package versions; dependencies between packages | a namespaced unlocked package cannot depend on one without a namespace; two packages with the same component name cannot share an org | namespaces and dependency rules decided up front |
| **Splunk apps and add-ons** [SP1] | app package with a manifest (packaging toolkit) | Splunkbase or private upload | **AppInspect** checks every package; a pass is needed for Splunk Cloud | automated vetting before a module may load |
| **Elastic plugins** [E1][E2] | JVM plugin zip | **installed on every node**, each node restarted; most plugins must match the **exact** Elasticsearch version, a node refuses to start otherwise | plugins built by Elastic or third parties | the cost of exact-version binding; Drishti keeps it only for a first-party module that uses kernel internals |
| **Grafana plugins** [G1] | data source, panel or **app** (an app bundles data sources, visuals and dashboards) | plugin catalog | **five signature levels**: Grafana, Enterprise, Community, Commercial, Private | visible signature tiers |
| **Apache Superset** [SU1] | extensions: front-end and back-end core packages and a CLI | being built; the vendor page says the architecture is under active development | n/a | none yet |
| **Kubernetes operators** [K1] | custom resources plus a controller | deploy on demand, backups, upgrades with schema changes, leader election | cluster RBAC | the placement and singleton work is the same problem |

### 3.5 BI tools: Power BI, Tableau, Qlik, Looker

All four extend a **closed BI core** with front-end pieces; none loads server-side domain logic as a package:

- **Power BI**: custom visuals and connectors; the full comparison is [BI_AND_POWER_BI.md](BI_AND_POWER_BI.md).
- **Tableau**: dashboard extensions and viz extensions, JavaScript with a manifest, served over HTTPS [T1].
- **Qlik Sense**: visualization extensions; the Extension API is legacy and nebula.js is recommended for new work; Qlik Cloud takes a zip
  of up to 30 MB [Q1][Q2].
- **Looker**: the Marketplace holds **blocks** (prebuilt LookML models with dashboards and Explores) and **applications** built on the
  extension framework, which an administrator must enable [L1]. LookML blocks are the nearest BI analogue of a Drishti module's packs.

## 4. Where Drishti is genuinely different

Each item is **built** unless it says otherwise; the design is [KERNEL_AND_MODULES.md](../architecture/KERNEL_AND_MODULES.md), the tour
[HOW_IT_FITS.md](../../HOW_IT_FITS.md).

1. **A domain-free kernel for data and BI.** The kernel knows no trade or gene; banking, market data, capital markets and economy are
   modules, and the genomics sample proves the kernel carries no finance. Risk vendors are finance platforms first; plug-in platforms
   (Snowflake, Grafana) are domain-free but carry no risk engine.
2. **The `.dmod` package, loaded at run time.** Server part (Java, Python compute, packs) and console part in one file; auto-deploy from
   a folder, a plan per node, four-eyes apply, two-phase activation with rollback, and **clean unload** (every surface withdrawn, saved work
   marked *needs module X*). Atoti's unit is a Maven project compiled in [A4][A6]; Elastic needs a restart of every node [E1].
3. **Distributions (white label).** One kernel build and one console build make Drishti Risk, Finance, Genomics and All, each from one
   file ([DISTRIBUTIONS.md](../architecture/DISTRIBUTIONS.md)). Atoti offers branding of one app [A1]; we found no risk vendor shipping
   several products from one file.
4. **Modules declare data needs; the kernel binds them.** A module never brings connectors; the site binds its lakes and sources
   (`config/bindings.yaml`, Admin → Data bindings). Snowflake's consumer-granted privileges are the nearest idea [S1].
5. **Lake-native.** Data stays in open Delta Lake or Apache Iceberg tables read through the standard libraries, not in a vendor store.
6. **Clustered compute with placement.** Each module part is placed by node or role (`config/placement.yaml`), with a singleton election
   behind one interface; the same problems Kubernetes operators solve outside the product [K1].
7. **VaR whose numbers can be re-derived.** Contributions that add up, the day's change explained, a playground whose formula workbook
   recomputes the figure, the regulatory backtest and golden tests ([VAR_GUIDE.md](../guides/VAR_GUIDE.md)). This is **proof a reviewer
   can check, not regulator acceptance**: no supervisor has reviewed it and no model validation pack exists.

## 5. Where Drishti is behind

- **Maturity.** The kernel and module runtime are new; the Ed25519 publisher signature on a `.dmod` is designed and planned (j); until
  then a first-party module is verified by its id and content hash against the kernel's shipped list. The Console as a separate Spring Boot
  tier is planned (i5).
- **Ecosystem.** One substantial industry module (banking), no third-party module author, no marketplace or site repository (planned,
  phase P5), no partner network. Snowflake, Salesforce, Splunk and Grafana each run a public catalogue [S1][SP1][G1].
- **Support.** One author; no support organisation, no SLA, no 24x5 desk of the kind MSCI states [R1].
- **Certifications and proof.** No SOC 2 or ISO 27001, no ISDA certification for FRTB-SA or SIMM, no regulator acceptance, no reference
  client (details in [COMPETITIVE_ANALYSIS.md](COMPETITIVE_ANALYSIS.md#13-the-five-biggest-gaps)).
- **No SaaS.** FIS, Numerix, Murex and Calypso all offer a managed cloud service [F2][N2][M2][C3]; Drishti is self-hosted only.
- **No pricing or market data of its own.** MARS and RiskManager bring both [B1][R1]; Drishti reads what the client's engines produce.
- **Scale proof at cluster size** is limited ([BENCHMARK.md](../admin/BENCHMARK.md)).

## 6. What to borrow (ideas only)

| Idea | From | How it could apply |
|---|---|---|
| Signature tiers shown to the user (first-party, partner, community, private) | Grafana [G1] | Admin → Modules and Health show the tier beside each module, not only first or third party |
| Automated vetting tool run before publish and before load, with a cloud tag | Splunk AppInspect [SP1] | the SDK's conformance checks (C1-C15) as a standalone report a site's security team reads |
| Privileges granted by the installing site, listed at install time | Snowflake Native Apps [S1] | the bindings screen asks for each declared need at install, as a consent page |
| Versions and patches with gradual rollout | Snowflake Native Apps [S1] | patch releases of a module on a subset of nodes before all |
| Namespaces and dependency rules fixed before third parties arrive | Salesforce [SF1][SF2] | reserve module id prefixes; refuse a dependency from a signed module to an unsigned one |
| Avoid exact-version binding for third parties | Elastic, by contrast [E1] | keep the kernel API range (`kernel: ">=1.0 <2.0"`) for every module; the exact kernel build only for a first-party module with a kernel-internals allowance |
| Packaged model content (a model, dashboards, explores) as a module kind of its own | Looker blocks [L1] | a content-only module (packs, Sutras, reports) with no Java, easiest to author |
| Source-delivered accelerators a client can fork | Atoti solutions [A4][A6] | publish the genomics sample and a minimal risk module as starter projects for the SDK |
| Continuous delivery with no scheduled upgrades | FIS on AWS [F2] | relevant only if a managed offering is ever decided |

## 7. Sources

Every link read on **2026-11-11**. Statements are the publishers' own; where a search summary was all we could read, the page is listed
but the statement is marked as the vendor's claim.

**ActiveViam Atoti**

- [A1] [Atoti Python SDK, Extending the app](https://docs.activeviam.com/engine/python-sdk/0.9/guides/extending_the_app.md)
- [A2] [Atoti Community Edition licence agreement](https://atoti.activeviam.com/eula/)
- [A3] [Atoti Python SDK, Unlock all features](https://docs.activeviam.com/engine/python-sdk/0.9/guides/unlocking_all_features)
- [A4] [Atoti Market Risk 5.3 release notes](https://assets.activeviam.com/products/solutions/mra/5.3/online-help/dev/dev-release/release-notes.html)
- [A5] [Atoti Market Risk 6.0, Limits](https://docs.activeviam.com/solutions/market-risk/6.0/limits)
- [A6] [Atoti Limits 6.2, Getting started](https://docs.activeviam.com/atoti-intelligence/workflows/limits/6.2/dev/getting-started)

**Trading and risk platforms**

- [M1] [Murex, MX.3 architecture](https://www.murex.com/en/solutions/technology/mx3-architecture)
- [M2] [Murex, MXSaaS](https://www.murex.com/cloud-saas)
- [M3] [Microsoft, Murex MX.3 on Azure (reference architecture)](https://learn.microsoft.com/en-us/azure/architecture/example-scenario/finance/murex-mx3-sql)
- [C1] [Nasdaq Calypso](https://www.nasdaq.com/products/fintech/calypso)
- [C2] [Nasdaq Calypso on AWS Marketplace](https://aws.amazon.com/marketplace/pp/prodview-3uyp6gg5wdnsa)
- [C3] [Nasdaq CapCloud](https://www.nasdaq.com/solutions/fintech/services/nasdaq-capcloud)
- [F1] [FIS Enterprise Risk Suite product sheet](https://www.fisglobal.com/-/media/fisglobal/files/PDF/product-sheet/FIS-Enterprise-Risk-Suite-Product-Sheet-updated.pdf)
- [F2] [FIS launches Enterprise Risk Suite on AWS (Business Wire, 19 May 2026)](https://www.businesswire.com/news/home/20260519958293/en/)
- [N1] [Numerix on AWS Marketplace](https://aws.amazon.com/marketplace/pp/prodview-bn5mee2tm75t4)
- [N2] [Numerix, Oneview for XVA](https://www.numerix.com/oneview-xva)

**Hosted risk services**

- [R1] [MSCI RiskMetrics RiskManager](https://www.msci.com/data-and-analytics/risk-management-solutions/riskmetrics-riskmanager)
- [B1] [Bloomberg MARS](https://professional.bloomberg.com/products/risk/mars)
- [B2] [Bloomberg, An API renaissance for risk technology](https://www.bloomberg.com/professional/insights/data/an-api-renaissance-for-risk-technology)

**Plug-in platforms**

- [S1] [Snowflake, About the Snowflake Native App Framework](https://docs.snowflake.com/en/developer-guide/native-apps/native-apps-about)
- [SF1] [Salesforce, Avoid namespace collisions in second-generation managed packages](https://developer.salesforce.com/docs/platform/pkg2-dev/guide/sfdx-dev-dev2gp-namespace-collisions.html)
- [SF2] [Salesforce, Avoid namespace collisions (unlocked packages)](https://developer.salesforce.com/docs/platform/sfdx-dev/guide/sfdx-dev-unlocked-pkg-namespace-collisions.html)
- [SP1] [Splunk, AppInspect](https://dev.splunk.com/enterprise/docs/developapps/testvalidate/appinspect)
- [E1] [Elastic, elasticsearch-plugin command](https://www.elastic.co/docs/reference/elasticsearch/command-line-tools/elasticsearch-plugin)
- [E2] [Elastic, Elasticsearch plugins](https://www.elastic.co/docs/reference/elasticsearch/plugins)
- [G1] [Grafana, Plugin management](https://grafana.com/docs/grafana/latest/plugins/)
- [SU1] [Apache Superset, Extensions overview](https://superset.apache.org/developer_portal/extensions/overview)
- [K1] [Kubernetes, Operator pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)

**BI**

- [T1] [Tableau Extensions API](https://tableau.github.io/extensions-api/docs/trex_api_about.html)
- [Q1] [Qlik, Extension API](https://qlik.dev/extend/extensions/extension-api.md)
- [Q2] [Qlik Cloud, Managing extensions](https://help.qlik.com/en-US/cloud-services/Sense_Hub/Admin/mc-extensions.htm)
- [L1] [Looker Marketplace overview](https://docs.cloud.google.com/looker/docs/marketplace-overview)

**Not verified:** the internal module boundaries of MX.3, Calypso, the FIS suite and Oneview (no public package format found, which is not
proof that none exists); Atoti's current Community Edition user limit (the site and the EULA differ); the current state of Superset's
extension architecture; whether Grafana's five signature levels are unchanged in the current release (read from the plugin management
page and Grafana's blog).
