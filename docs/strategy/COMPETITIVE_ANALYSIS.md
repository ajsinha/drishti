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
# Competitive analysis

> **Internal — not for customers.** This document names other companies' products and gives our judgement of them. It is shown
> in the in-product Help to administrators only, and it is not linked from any customer-facing index. Do not paste it into a deck,
> a proposal or an e-mail to a prospect. Facts about other vendors are taken from their public material and the press, cited in
> [section 7](#7-sources); where we could not verify a claim we say **not verified**.

**Status: analysis and product description** (internal; owner: the product owner; as of 2026-11-01; reviewed at every release). The architecture comparison (kernel, modules, distributions against plug-in platforms) is [Part II · Kernel and modules against plug-in platforms](#part-ii--kernel-and-modules-against-plug-in-platforms).

## Contents

1. [Summary](#1-summary)
2. [How to read this document](#2-how-to-read-this-document)
3. [Banking, finance and risk](#3-banking-finance-and-risk)
4. [General data and analytics: Drishti as an any-data platform](#4-general-data-and-analytics-drishti-as-an-any-data-platform)
5. [Pricing, packaging and go-to-market](#5-pricing-packaging-and-go-to-market)
6. [Risks to this analysis, and keeping it current](#6-risks-to-this-analysis-and-keeping-it-current)
7. [Sources](#7-sources)

---

## 1. Summary

### 1.1 Drishti's position in one paragraph

Drishti is a self-hosted, config-driven data terminal with business intelligence and on-demand risk on top. A short YAML grammar
(Rachana, written as Sutras), domain packs and an inference engine turn any connected data into linked, live, keyboard-driven
screens; Rūpaka adds models, reports and boards on the same data; and the risk engines compute VaR, expected shortfall, stressed VaR
and counterparty exposure (EE, PFE, EPE, EEPE) **for any scope a person can type, from the trade-level inputs the bank's pricing
engines already produce**, with contributions that add up, explanations of every number and of every day's change, and a pre-deal
what-if. In banking it sits **between the pricing engines and the people**: it competes head-on with risk aggregation cubes
(ActiveViam Atoti, Opensee) and with the aggregation and reporting layers of enterprise risk systems, and it complements the trading
platforms and pricing libraries it reads from. Outside banking it is a domain-grammar alternative to BI tools and internal-app
builders for organisations that want one governed, on-premises, explainable view of their own data. It is young: one author, no
reference client, no certifications; FRTB-SA and SIMM are built (uncertified), while FRTB-IMA, SA-CCR and the XVA family are planned,
not built.

### 1.2 The five strongest differentiators

1. **Risk for any scope, computed when asked.** VaR, ES, stressed VaR and exposure for the bank, a desk, a counterparty, a stored
   portfolio, a rule, an ad-hoc basket or any set algebra of them (`BANK - DESK:CREDIT`), drilled to the trade, with diversification
   and netting applied rather than summed away. **Measured:** 1,000,000 positions and 15,000 portfolios on one server with a 6 GB
   heap answered 183, 138 and 124 requests a second with 50, 100 and 200 users and no failure ([BENCHMARK](../admin/BENCHMARK.md)).
2. **Every number explains itself.** "How this number was built" on every figure; the VaR change explained (the day's move split into
   steps with their drivers, as a waterfall, a matrix and sentences); the VaR Playground with a formula workbook that recomputes the
   figure in Excel; the official end-of-day record with its inputs' versions; a data contract for every kind. Few competitors show the
   arithmetic to the person who has to defend it.
3. **One grammar for every domain.** Screens are paragraphs of YAML, not programs; packs bring an industry; the core knows none. The
   same product serves a risk desk, a market-data team, public economic data and the example packs (logistics, genomics, retail
   banking). Editions and lenses (Risk, Trading, Market, World built) turn one install into a focused product without a fork.
4. **Open storage, light footprint.** Delta Lake and Apache Iceberg (local disk, S3, AWS S3 Tables) read and written by Drishti's own
   engines without Hadoop; JSON Lines in, open tables out; the bank's own Databricks or Iceberg tables readable in place. One Spring
   Boot server and one Python console; SQLite by default; runs offline and air-gapped; on Windows with Java and Python only (no
   executable); every front-end asset vendored, no CDN, strict content security policy.
5. **Terminal-grade UX and BI on the same data.** A Bloomberg-style command line with type-ahead, function keys, live ticking with
   measured latency, links across systems, history and "known at" for any past business day, a phone display with configured tiers,
   and BI (models, reports, boards, governance) that drills from a chart into the record. AI is optional, off by default and
   provider-neutral.

### 1.3 The five biggest gaps

1. **No pricing models and no market-data content of its own.** Drishti consumes P&L vectors, scenario PVs and exposure paths; it
   does not price a trade. A bank without a capable upstream engine cannot use the risk side alone. (A pre-deal pricing service from
   QLRisk is planned.)
2. **Most capital engines are planned, not built:** FRTB-IMA, SA-CCR, the XVA family (CVA, BA-CVA, SA-CVA, FVA, MVA, KVA) and
   wrong-way risk. These are what the incumbents sell first. FRTB-SA (the sensitivities-based method, the DRC and the RRAO) and ISDA
   SIMM are built, with their playgrounds ([FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md), [SIMM_GUIDE.md](../guides/SIMM_GUIDE.md)), but
   not certified, and SIMM needs the bank's licensed ISDA parameter file.
3. **No institutional proof:** no reference client, never deployed at a supervised institution, no SOC 2 or ISO 27001, no model
   validation pack, no regulator acceptance, one author and no support organisation.
4. **Scale proof is single-server.** 1,000,000 positions is measured on one workstation; the three-node cluster is measured at
   200,000 positions only, and its contribution queries were slower than one server's. A tier-1 bank will ask for a cluster run at its
   own size, on its own hardware.
5. **Ecosystem depth.** Fewer connectors than the platforms, BI authoring short of Power BI (no DAX, no Power Query, no custom-visual
   marketplace, no publish-to-web), no SaaS offering, no community, no partner network, no marketplace listing.

### 1.4 The recommended pitches

**First pitch (now): "the explainable risk aggregation layer over the engines you already have."** For a market risk department (or
a counterparty risk team) that has P&L vectors or scenario PVs from Murex, Calypso, an in-house library or a vendor engine, and that
answers ad-hoc scope questions by spreadsheet or by re-running a batch. Offer a pilot on the bank's own vectors, on its own servers:
VaR, ES and stressed VaR for any scope, backtesting with the traffic light, the day's change explained, the playground workbook for
model validation, and the regulatory VaR tables. Position as **complement** to the trading platform and **replace** for an aggregation
cube or a spreadsheet layer. Do not lead with capital.

**Long-term pitch (after the planned releases): "one risk data platform, computed from trade-grain inputs, explained to the cent."**
Market risk, counterparty risk, capital and oversight on one open lake, replacing an aggregation cube, the reporting layer of an
enterprise risk system, an exposure aggregator, an XVA calculator (where its pricing is upstream) and part of a regulatory reporting
tool, while the bank keeps its pricing engines. Outside banking: **the domain-grammar terminal and BI for regulated organisations**
that want on-premises, explainable views of any data.

---

## 2. How to read this document

### 2.1 Built today against planned

Drishti's own capabilities are given in two columns throughout:

- **Today**: built, released and documented (the guides linked in the text say how).
- **Planned**: in the agreed release plan ([IMPLEMENTATION_PLAN](../architecture/IMPLEMENTATION_PLAN.md)), proposed and not started.
  We name the release theme, not the number, because numbers move. Done: *sensitivities and market-risk capital* (position
  sensitivities, FRTB-SA, SIMM, the market-risk cube: today). The themes to come, in order: *counterparty engines* (SA-CCR, the counterparty cube, FVA, the
  exposure playground); *Java 25 and performance*; *oversight* (breaches and escalation, the board pack, market-data checks);
  *regulatory reporting* (BCAR, Pillar 3 MR1/MR2, FFIEC 102, §217.212, Volcker); *native performance* (BLAS, direct lake scans);
  *XVA and wrong-way risk* (CVA, BA-CVA, SA-CVA, MVA, KVA, WWR); *advanced market risk* (FRTB-IMA, pre-deal pricing through QLRisk);
  *executive* (the CRO and CIO lenses, liquidity, capital, climate).

A planned item is a design, not a promise to a customer: nothing in the plan starts without the product owner's go.

### 2.2 Evidence levels

| Level | Meaning | Where it is used |
|---|---|---|
| **Measured** | a number a run produced, with its machine, data and command | Drishti's performance ([BENCHMARK](../admin/BENCHMARK.md)); never extrapolated in this document |
| **Designed** | a written design in the repository, not built | Drishti's planned column |
| **Claimed** | the vendor's own public statement (web page, press release, award write-up) | almost every competitor fact; we have not tested any competitor's product |
| **Not verified** | we looked and did not find a public source | said where it applies |

### 2.3 The ratings

● strong, a core part of the offering · ◐ partial, an add-on, limited or by configuration · ○ absent or not the product's purpose ·
**?** not verified. A group's rating is the typical member's, from its public material; one member may be stronger or weaker than its
group. Ratings of competitors are judgements from claimed material, not tests.

---

## 3. Banking, finance and risk

### 3.1 The categories

| Category | Members considered | What they are | Drishti's relation |
|---|---|---|---|
| **A. Risk aggregation cubes** | ActiveViam Atoti; Opensee | in-memory or big-data aggregation of risk vectors and sensitivities with drill-down, what-if and FRTB accelerators | **direct competitor**, closest in shape |
| **B. Trading-platform risk modules** | Murex MX.3; Nasdaq Calypso; FIS Cross-Asset Trading and Risk (formerly Front Arena) and FIS Enterprise Risk Suite (formerly Adaptiv); Finastra Treasury and Capital Markets (Kondor, Summit, Opics; being sold to Apax funds) | front-to-back platforms with pricing, booking, risk and regulatory modules | **upstream source**; complement, coexist; replace only their aggregation and reporting screens |
| **C. Enterprise market and credit risk** | MSCI RiskMetrics RiskManager; Bloomberg MARS; Numerix Oneview; Quantifi; Moody's; S&P Global Market Intelligence (FRTB services); Vector Risk; SAS Risk Stratum; Oracle OFSAA | engines with models and often market data, hosted or installed | **compete** on aggregation, explanation and reporting; **complement** where they price |
| **D. Regulatory reporting** | Nasdaq AxiomSL; Regnology (now including Wolters Kluwer OneSumX and, signed, Moody's regulatory reporting and ALM) | report templates per regulator, data lineage, submission | **feed** today; **partial replace** for market-risk VaR reports when the regulatory-reporting release is built |
| **E. Governance, risk and compliance** | IBM OpenPages; MetricStream | risk registers, controls, issues, policies, workflows | **coexist**; Drishti supplies the numbers and breaches |
| **F. Buy-side platforms** | BlackRock Aladdin; State Street Charles River IMS; SimCorp (Deutsche Börse) | order management, portfolio management, risk, operations for asset managers | **coexist**; relevant only where a bank's treasury or asset-management arm compares |

### 3.2 The capability matrix

Drishti columns: **T** = today, **P** = after the planned releases. Groups as in 3.1. Read the legend in [2.3](#23-the-ratings).

| Capability | Drishti T | Drishti P | A. Cubes | B. Platforms | C. Enterprise risk | D. Reg reporting | E. GRC | F. Buy-side |
|---|---|---|---|---|---|---|---|---|
| VaR / ES for any scope, ad hoc, with true diversification | ● | ● | ● | ◐ | ◐ | ○ | ○ | ◐ |
| Stressed VaR, 10-day, period averages | ● | ● | ◐ | ● | ● | ◐ | ○ | ◐ |
| VaR change explained (day over day, by driver) | ● | ● (Shapley order planned) | ◐ | ◐ | ◐ | ○ | ○ | ◐ |
| Backtesting with traffic light | ● | ● | ◐ | ● | ● | ◐ | ○ | ◐ |
| FRTB-SA | ● (built, not certified) | ● | ● | ● | ● | ◐ | ○ | ○ |
| FRTB-IMA (ES by liquidity horizon, NMRF, PLA) | ○ | ● | ● | ● | ◐ | ○ | ○ | ○ |
| ISDA SIMM | ◐ (built, not certified; the bank's licensed parameter file) | ● | ◐ | ● | ● | ○ | ○ | ◐ |
| SA-CCR | ○ | ● | ◐ | ● | ● | ◐ | ○ | ○ |
| EE / PFE / EPE / EEPE for any scope, with netting and collateral | ● (from supplied paths) | ● | ◐ | ● | ● | ○ | ○ | ○ |
| XVA family (CVA, DVA, FVA, MVA, KVA; BA-CVA, SA-CVA) | ○ | ● (aggregation of supplied paths and inputs) | ◐ | ● | ● | ○ | ○ | ○ |
| Wrong-way risk | ○ | ● | ○ | ◐ | ◐ | ○ | ○ | ○ |
| Limits and breaches | ◐ (VaR limits per node, firm-wide limit) | ● (breaches, escalation, approvals) | ◐ | ● | ● | ○ | ● (as controls) | ● |
| Regulatory reports with lineage | ◐ (annual-report VaR, backtesting, Volcker VaR; controls, frozen runs, sign-off) | ● (BCAR, Pillar 3 MR1/MR2, FFIEC 102, §217.212) | ◐ | ◐ | ◐ | ● | ◐ | ◐ |
| Pre-deal what-if | ● (from supplied PVs / paths) | ● (with QLRisk pricing) | ● | ● | ● | ○ | ○ | ● |
| Intraday | ● (files and Kafka micro-batches on the morning run) | ● | ● | ● | ◐ | ○ | ○ | ◐ |
| Cube / pivot over risk | ◐ (the Pivot tab; BI models) | ● (market-risk and counterparty cubes on one core) | ● | ◐ | ◐ | ○ | ○ | ◐ |
| Playground / formula proof (Excel workbook that recomputes the figure) | ● (VaR; the explanation) | ● (one per metric) | ○ **?** | ○ **?** | ○ **?** | ○ | ○ | ○ |
| Persona views (desk head, risk manager, CRO, CIO) | ● (lenses: Risk, Trading, Market, World) | ● (Oversight, CRO, CIO) | ◐ | ◐ | ◐ | ○ | ● | ● |
| Phone | ● (configured tiers) | ● | **?** | **?** | ◐ | ○ | ◐ | ◐ |
| Open lake storage (Delta, Iceberg) | ● | ● | ◐ **?** | ○ | ○ | ◐ **?** | ○ | ○ |
| Scale, measured and published | ◐ (1M positions on one server; 200K on a 3-node cluster) | ● (cluster at 1M planned) | ● (claimed, client cases) | ● (claimed) | ● (claimed) | ● (claimed) | n/a | ● (claimed) |
| Deployment footprint | ● light (one server + console, SQLite default, air-gapped, Windows without exe) | ● | ◐ | ○ heavy | ◐ (many hosted) | ◐ | ◐ | ○ heavy |
| Pricing models | ○ | ◐ (QLRisk for pre-deal) | ◐ (Derivitec acquired, 2026) | ● | ● | ○ | ○ | ● |
| Market data content | ○ (shows the bank's; public-data packs) | ○ | ○ | ◐ | ● (Bloomberg, MSCI, S&P, Moody's) | ○ | ○ | ● |
| Institutional proof (clients, certifications, regulator use) | ○ | ○ (not a build item) | ● | ● | ● | ● | ● | ● |

**Read:** today Drishti is strongest where the cubes are strong (any-scope VaR, drill-down, what-if) and adds what they do not sell as a
core feature (explanation of the day's change, the formula workbook, terminal UX, open lake). It is absent where the platforms and
enterprise engines earn their fees (capital engines, pricing, market data, institutional proof). After the planned releases the
engine gap closes; the pricing, market-data and proof gaps do not close by building.

### 3.3 Per competitor

Each entry: what it is, its strengths (claimed), where Drishti wins, where it loses, and how to position.

#### 3.3.1 ActiveViam Atoti

- **What it is.** An in-memory analytical engine and a set of risk solutions on it (market risk, FRTB, others). ActiveViam is majority
  owned by Nordic Capital (2024) and in 2026 acquired Derivitec, a cross-asset derivatives and FX analytics firm, adding pricing to
  aggregation. Clients named in public releases include Leonteq (SA-FRTB, February 2026) and Natixis (front-office real-time risk and
  P&L).
- **Strengths (claimed).** On-the-fly aggregation of FRTB metrics over large datasets; ISDA certified vendor status for FRTB; repeated
  Risk.net awards for FRTB; drill-down that clients cite; now pricing through Derivitec.
- **Where Drishti wins.** The day's change explained and the formula workbook; any-scope algebra typed as text; a terminal over the
  whole bank's data, not only risk cubes; BI and boards on the same data; open lake storage; a footprint a risk department can run
  itself; one grammar for every screen.
- **Where Drishti loses.** FRTB-SA is certified there and built but uncertified here; FRTB-IMA is built there and planned here; reference clients and awards; a sales and
  support organisation; pricing now in the same house.
- **Position.** **Replace** for VaR aggregation and explanation in a market-risk department; **coexist** where Atoti FRTB is already
  live (Drishti as the terminal and explanation layer reading the same vectors) until the market-risk capital release is built.

#### 3.3.2 Opensee

- **What it is.** A Paris-based real-time data management and risk analytics platform for banks and hedge funds, on any cloud,
  on-premises or hybrid. Public cases: Société Générale (liquidity and RWA aggregation on demand), Crédit Agricole CIB (front-office
  market risk), Taula Capital (real-time VaR and stress testing, six-week rollout).
- **Strengths (claimed).** Self-service aggregation over large granular datasets; real-time re-aggregation of VaR when positions
  change; cost reductions of up to 90% (vendor claim, not verified).
- **Where Drishti wins.** Explanation and playgrounds; terminal UX and links across systems; open-format lake rather than a proprietary
  store (Opensee's storage format: **not verified**); exposure as well as VaR today; packs beyond risk.
- **Where Drishti loses.** Tier-1 bank references; data volumes proven in production; a team and support.
- **Position.** **Replace** in the same department-level sale; lead with explanation and the measured single-server footprint.

#### 3.3.3 Murex MX.3

- **What it is.** A front-to-back trading, risk and processing platform. Its enterprise risk and regulatory suite covers internal market
  risk, FRTB (SA and IMA), XVA, SA-CCR, credit risk and initial margin; Murex states that its FRTB-SA, SA-CCR, SIMM and SA-CVA are
  validated against ISDA unit tests; XVA can run on premises, in the cloud or hybrid, with pre-trade incremental XVA.
- **Strengths (claimed).** Pricing and booking in one place; prepackaged regulatory content; many Risk.net awards (XVA, enterprise risk,
  vendor of the year).
- **Where Drishti wins.** Cross-system views (Murex shows Murex's data); any-scope questions without a batch; explanation of the move;
  speed of a new screen (a Sutra, not a project); cost and footprint of the aggregation layer.
- **Where Drishti loses.** Everything that needs pricing; capital engines; the incumbent's integration into the bank's processes.
- **Position.** **Complement.** Murex produces the P&L vectors, scenario PVs and exposure paths; Drishti aggregates, explains and
  shows them with the rest of the bank's data. Never pitch "replace Murex".

#### 3.3.4 Nasdaq Calypso

- **What it is.** A cross-asset trading, risk and collateral platform. Calypso and AxiomSL were combined as Adenza, which Nasdaq
  acquired on 1 November 2023 (total consideration about USD 10 billion per Nasdaq's filing); Calypso now sits in Nasdaq's Capital
  Markets Technology division. (The brief named "Calypso/ION": the current owner is Nasdaq.)
- **Strengths (claimed).** Front-to-back with collateral; Nasdaq's scale; the AxiomSL reporting sibling.
- **Where Drishti wins / loses / position.** As for Murex: **complement**, read its outputs; win on cross-system, any-scope and
  explanation; lose on pricing, capital and incumbency.

#### 3.3.5 FIS (Cross-Asset Trading and Risk; Enterprise Risk Suite, formerly Adaptiv)

- **What it is.** Front Arena is now branded the FIS Cross-Asset Trading and Risk platform (with a 2024 digital trading storefront and
  an April 2026 buy-side extension). Adaptiv is now the FIS Enterprise Risk Suite: front-office XVA and initial margin, counterparty
  credit risk under a global limits framework, and XVA data and calculation services.
- **Strengths (claimed).** Counterparty risk product awards; real-time exposure against limits; XVA sensitivities by algorithmic
  differentiation (from an Adaptiv Analytics sheet of uncertain provenance, treat as **not verified**).
- **Where Drishti wins.** Exposure for any scope typed as text, with netting and collateral on each path and an exact pre-deal what-if,
  explained down to the trade; the counterparty cube is designed to beat Adaptiv's pivots on non-additive totals
  ([CCR cube lens](../architecture/LENSES.md#7-ccr-cube-lens)); open lake; footprint.
- **Where Drishti loses.** Simulation (Drishti reads paths, it does not generate them); limits workflow (planned); SA-CCR and XVA
  (planned); FIS's client base.
- **Position.** **Coexist** today (Drishti reads the simulator's paths); **replace** the aggregation and limit-monitoring screens after
  the counterparty-engines and oversight releases, where the bank keeps its own simulator.

#### 3.3.6 Finastra Treasury and Capital Markets

- **What it is.** Kondor, Summit and Opics, serving more than 340 institutions (claimed). Finastra agreed in May 2025 to sell the unit
  to funds advised by Apax Partners, closing expected in the first half of 2026, with a new brand; completion and the new name are
  **not verified** as of 2026-11-01. Finastra has also delivered Vector Risk (VaR, PFE, CVA as SaaS) to clients such as Banco Sofisa.
- **Position.** **Complement**; an ownership change is a window in which its clients review their risk stack. Watch it.

#### 3.3.7 MSCI RiskMetrics RiskManager

- **What it is.** A hosted enterprise risk platform combining VaR (several simulation methods), factor, stress testing (historical and
  hypothetical library), liquidity and counterparty analytics on the RiskMetrics engine, with MSCI's market data and models.
- **Strengths (claimed).** Hosted, data included, a long record with banks and asset managers, regulatory stress-test reporting.
- **Where Drishti wins.** On-premises, the bank's own scenarios and vectors, explanation and workbooks, terminal and BI on the same
  data, any-scope algebra, no data leaving the bank.
- **Where Drishti loses.** Models and data out of the box; a hosted service; MSCI's brand with boards and regulators.
- **Position.** **Replace** where the bank already produces its own vectors and uses RiskManager mainly to aggregate; otherwise
  **complement** (RiskManager's outputs loaded as P&L vectors).

#### 3.3.8 Bloomberg MARS

- **What it is.** The Multi-Asset Risk System, on the Terminal and by API: valuations, front office, market risk, XVA, collateral,
  hedge accounting and climate modules; Bloomberg states FRTB-SA, BA-CVA, SA-CCR and SIMM coverage. Clients named include Commercial
  Bank International and Bank SinoPac.
- **Strengths (claimed).** Bloomberg's pricing and data; the Terminal's reach; traders already live there.
- **Where Drishti wins.** The bank's own data from every system (MARS is Bloomberg-centred); on-premises; the terminal experience on
  in-house data, which is our stated bar ("compare with Bloomberg"); explanation.
- **Where Drishti loses.** Pricing, data, capital calculations today, and the Terminal's place on every desk.
- **Position.** **Coexist / complement.** Drishti is the terminal for what Bloomberg does not hold.

#### 3.3.9 Numerix Oneview

- **What it is.** A cross-asset front-to-risk platform: pricing, XVA, counterparty credit risk and enterprise market risk. Owned by
  Genstar Capital since 2022; acquired FINCAD and PolyPaths (2023) and Kynex (2024); a multi-year AWS collaboration (2025). Oracle's
  market-risk product uses Numerix analytics.
- **Position.** **Complement**: Numerix prices, Drishti aggregates and explains. Strong partner candidate (see 5.3).

#### 3.3.10 Quantifi

- **What it is.** Pricing, market risk, counterparty risk and full XVA (CVA, DVA, FVA, KVA, MVA, economic capital) with pre-trade
  incremental XVA and PFE; extended to commodities as a supplement to energy trading systems. Named a Chartis category leader for
  sell-side enterprise market risk (January 2025). Clients named include Helaba and OeKB.
- **Position.** **Complement** for pricing and XVA; **compete** on the aggregation and reporting screens for mid-size banks.

#### 3.3.11 Moody's

- **What it is today.** Moody's agreed in December 2025 to sell its Regulatory Reporting & ALM Solutions business to Regnology; a later
  report says the deal completed (completion date **not verified**). Whether RiskAuthority moved with it: **not verified**. Moody's
  remaining analytics business is described in its 2025 annual report as cloud subscription businesses for banking, insurance and KYC.
- **Position.** Less a competitor for trading-book risk than before; track the Regnology combination instead (3.3.15).

#### 3.3.12 S&P Global Market Intelligence (FRTB)

- **What it is.** FRTB services built around data: the modellability service (RFET with real price observations, a bucketing API,
  pre-configured and tailorable RFET methodology) and a traded market risk solution for IMA and SA capital, ES against SES scenario
  generation and NMRF proxying.
- **Where Drishti wins / loses.** Drishti has no price-observation data and no RFET; S&P has no terminal or on-demand any-scope VaR
  over the bank's vectors (**not verified** in detail).
- **Position.** **Complement**: RFET results become an input to the planned FRTB-IMA.

#### 3.3.13 Vector Risk

- **What it is.** An Australian provider of cloud trading-book risk software: VaR, FRTB-SA, SA-CCR, PFE and XVA; delivered through
  Finastra to Banco Sofisa with a go-live within 30 days (claimed). No acquisition found.
- **Where it matters.** It is the closest "light and fast" competitor in spirit, for small and mid-size banks, and it prices. Drishti
  wins on explanation, terminal, BI and on-premises; loses on capital engines and pricing today.
- **Position.** **Compete** in the small-bank segment after the capital releases; before then, avoid head-to-head.

#### 3.3.14 SAS Risk Stratum and Oracle OFSAA

- **SAS.** Risk Stratum is SAS's integrated risk platform; recent recognition is for ALM; for market risk SAS points to SAS Risk
  Dimensions and the SAS Risk Engine (SAS Market Risk for Banking withdrawn from 9.4M7). Market-risk depth on Stratum in 2025–26:
  **not verified**.
- **Oracle OFSAA.** Oracle Financial Services market risk includes historical-simulation FRTB IMA, backtesting and P&L attribution,
  modellable and non-modellable risk factor identification, stressed capital add-ons, with Numerix analytics.
- **Position.** Both are enterprise data-and-model suites sold to the whole bank. **Coexist**; Drishti is the explanation and terminal
  layer on top, and can replace their market-risk dashboards.

#### 3.3.15 Regulatory reporting: Nasdaq AxiomSL and Regnology (with OneSumX)

- **What they are.** AxiomSL (now Nasdaq, via Adenza) and Regnology are regulatory reporting platforms with templates per regulator.
  Regnology bought Wolters Kluwer's Finance, Risk and Regulatory Reporting unit, including OneSumX for Finance and for Risk (completed
  1 December 2025, enterprise value about EUR 450 million), and signed for Moody's Regulatory Reporting & ALM (December 2025).
- **Where Drishti wins.** For market-risk VaR reports only: the figures are computed from trade-grain inputs with frozen runs, lineage
  to inputs' versions and maker-checker sign-off, so the VaR tables need no separate reconciliation.
- **Where Drishti loses.** Breadth (every regulator, every return), submission formats, the regulators' familiarity.
- **Position.** **Feed** them today (the official table and the published results); **replace** only the market-risk VaR schedules
  after the regulatory-reporting release, where a bank wants them closer to the numbers.

#### 3.3.16 Governance: IBM OpenPages and MetricStream

- **What they are.** GRC platforms: risk registers, controls, issues, policies, with workflow. OpenPages integrates watsonx.ai
  through an API design and was named a leader in the IDC MarketScape for GRC software 2025 (claimed); release 9.1.2 on premises and
  hosted (September 2025). MetricStream's 2025 announcements: **not verified**.
- **Position.** **Coexist.** Drishti's planned breach and escalation workflow is for risk limits on computed numbers, not enterprise
  GRC. Offer a feed of breaches and evidence into the GRC system.

#### 3.3.17 Buy-side: BlackRock Aladdin, Charles River, SimCorp

- **What they are.** Aladdin (BlackRock): portfolio management, risk and operations, also sold as a standalone risk product. Charles
  River IMS (State Street): order and portfolio management with Axioma risk models and MSCI partnerships. SimCorp (Deutsche Börse,
  completed 2023): integrated investment management, SimCorp One.
- **Position.** Out of scope for the first pitch. Relevant only where a bank's asset-management or treasury arm compares, and where
  the funds pack (US funds and ETFs from SEC filings) is shown. **Coexist.**

### 3.4 After everything is built: which stacks Drishti could replace

Assumes every planned release is built and the bank keeps its pricing engines and simulators (Drishti does not price).

| A bank's stack today | What Drishti replaces | What stays |
|---|---|---|
| Murex or Calypso + Atoti (or Opensee) for aggregation + spreadsheets for ad-hoc scopes | the cube and the spreadsheets: any-scope VaR, ES, stressed VaR, FRTB-SA and IMA aggregation, the market-risk cube, explanation, playgrounds | the trading platform (pricing, P&L vectors, booking) |
| Trading platform + FIS Enterprise Risk Suite (Adaptiv) for exposure and limits + an XVA calculator | exposure aggregation, SA-CCR, the counterparty cube, limits and breaches, the aggregation and reporting of XVA (CVA, BA-CVA, SA-CVA, FVA, MVA, KVA) and wrong-way risk | the Monte Carlo simulator and pricing, unless QLRisk covers them |
| An enterprise risk system (MSCI, SAS, Oracle) used mainly to aggregate the bank's own vectors | the aggregation, reports, dashboards and the regulatory VaR tables | data vendors' market data and models where used |
| A reg-reporting tool for every return | the market-risk VaR schedules (BCAR 90.010, Pillar 3 MR1/MR2, FFIEC 102, §217.212, Volcker VaR), with lineage | the reporting tool for every other return |
| BI (Power BI, Tableau) over a risk warehouse | the risk dashboards, boards and the CRO and CIO views | enterprise BI for the rest of the bank |

**The headline once built:** one product can retire **an aggregation cube + the aggregation and limit layer of a counterparty system + the
reporting side of an XVA vendor + the market-risk part of a reg-reporting tool + the risk dashboards in BI**, on an open lake the bank
owns. Each replacement is a separate decision with its own validation; a bank will make them one at a time.

### 3.5 What still decides deals

These do not come from building features; each needs its own plan.

| Factor | Why it decides | Drishti today | What would close it |
|---|---|---|---|
| **Model validation** | second-line validation must sign off any number that feeds capital or limits | the playground workbooks, data contracts and the official record help a validator | a validation pack per metric: method documents, independent test results, the ISDA unit tests for SIMM and SA-CCR when built, benchmark against a vendor on the bank's data |
| **Reference client** | the first question of every bank | none | one pilot in a market-risk department, converted into a named reference or an anonymous case with figures |
| **SOC 2 / ISO 27001** | vendor onboarding at any regulated firm | none (the controls exist in the product: audit, entitlements, masks, CSP, no CDN; the organisation is not certified) | a company with controls and an audit; until then, on-premises deployment keeps data inside the bank, which shortens but does not skip vendor review |
| **Support** | 24x7 for anything feeding official numbers | one author | a support partner or team; documented runbooks exist ([runbooks](../guides/OPERATIONS_GUIDE.md#201-source-down-or-slow)) |
| **Scale proof on a cluster** | tier-1 banks hold millions of positions and need high availability | 1M on one server measured; 200K on three nodes measured | the cluster at 1M and then 2.3M positions measured on the bank's hardware, with failover |
| **Regulator acceptance** | internal models and reports are reviewed by supervisors | none | a client's model approval or report filing that uses Drishti's figures |
| **Vendor viability** | a bank buys for ten years | single author, proprietary licence | a company, escrow of source, or a partnership with an established vendor (5.3) |

---

## 4. General data and analytics: Drishti as an any-data platform

Drishti's core knows no industry: packs bring one. The shipped packs cover trading, market data, market risk, counterparty risk, world
economy, US equities (SEC EDGAR), GLEIF legal entities, US funds and ETFs, news and markets; the example packs show retail banking,
logistics, genomics, climate risk, liquidity risk and operational risk. This section compares Drishti with general data tools.

### 4.1 The categories

| Category | Members considered | Drishti's relation |
|---|---|---|
| **G. BI** | Microsoft Power BI; Tableau; Qlik; Looker; Apache Superset; Metabase | compete in regulated, on-premises, domain-specific use; coexist as enterprise BI |
| **H. Semantic / metrics layers** | dbt Semantic Layer (MetricFlow); Cube; AtScale | Drishti's packs and BI models are its semantic layer; coexist or read from theirs |
| **I. Lakehouse and query engines** | Databricks; Snowflake; Dremio; Starburst / Trino; DuckDB / MotherDuck; ClickHouse | Drishti stores in open formats and reads theirs; it is a consumer, not a warehouse |
| **J. Operational / real-time analytics** | KX (kdb+, KDB-X); Apache Pinot (StarTree); Apache Druid (Imply); Rockset (acquired by OpenAI, 2024, service wound down) | complement as a source; compete only on live views of entities |
| **K. Data-app builders** | Palantir Foundry; Retool; Streamlit; Appsmith | compete: Drishti writes screens from a grammar, they assemble screens by hand or code |
| **L. Observability dashboards** | Grafana | coexist; Drishti shows business entities, Grafana shows metrics and logs |
| **M. AI analytics assistants** | Copilot in Power BI; ThoughtSpot Spotter; Tableau Pulse; Looker conversational analytics (Gemini) | Drishti's AI is optional, provider-neutral, off by default; compete on control, lose on polish |

### 4.2 The capability matrix

| Capability | Drishti T | Drishti P | G. BI | H. Semantic | I. Lakehouse | J. Real-time | K. App builders | L. Grafana | M. AI assistants |
|---|---|---|---|---|---|---|---|---|---|
| One view of any entity, linked across systems | ● | ● | ○ | ○ | ○ | ○ | ◐ | ○ | ○ |
| Screens from a grammar (no hand-built screen per type) | ● (Sutras + inference) | ● | ○ | ○ | ○ | ○ | ◐ (Foundry ontology) | ○ | ○ |
| Domain packs (kinds, meanings, links, roles, contracts) | ● | ● | ◐ (templates, apps) | ◐ (metrics) | ○ | ○ | ◐ (Foundry) | ◐ (plugins) | ○ |
| Visual report authoring depth | ◐ (BI Studio: four panes, YAML tab) | ◐ (Power BI parity waves designed) | ● | ○ | ◐ (Databricks AI/BI) | ○ | ◐ | ◐ | ○ |
| Semantic model / metrics | ● (packs, BI models, row rules, masks) | ● | ● | ● | ◐ (metric views, semantic views) | ○ | ◐ | ○ | ◐ (rely on one) |
| Live, ticking views with measured latency | ● | ● | ◐ | ○ | ○ | ● | ◐ | ● | ○ |
| History, as-of and "known at" for any business day | ● | ● | ◐ | ○ | ● (time travel) | ◐ | ○ | ◐ | ○ |
| Explain a number (how it was built, provenance, lineage) | ● | ● | ◐ | ◐ | ◐ (lineage) | ○ | ○ | ○ | ◐ |
| Keyboard-first terminal | ● | ● | ○ | ○ | ○ | ◐ (q console) | ○ | ○ | ○ |
| Open formats (Delta, Iceberg) | ● read and write | ● | ◐ read | ◐ | ● | ◐ | ◐ | ○ | ○ |
| Connector breadth | ◐ (19 connector plugins) | ◐ | ● | ◐ | ● | ◐ | ● | ● | n/a |
| Write-back with four-eyes approval | ● (off by default) | ● | ◐ | ○ | ◐ | ○ | ● | ○ | ○ |
| AI: ask the data, explain, report from words | ◐ (optional, any provider, masks in prompts) | ● | ● | ◐ | ● | ○ | ● | ◐ | ● |
| On-premises, offline, air-gapped | ● | ● | ◐ (Power BI Report Server, Tableau Server) | ◐ | ◐ | ● | ◐ | ● | ○ |
| Windows without native executables | ● | ● | ◐ | n/a | ○ | ○ | ○ | ○ | ○ |
| SaaS offering | ○ | ○ | ● | ● | ● | ● | ● | ● | ● |
| Ecosystem, community, certifications | ○ | ○ | ● | ◐ | ● | ◐ | ● | ● | ● |

### 4.3 Per category

#### 4.3.1 BI: Power BI, Tableau, Qlik, Looker, Superset, Metabase

- **What they do best.** Visual authoring for analysts, very large ecosystems, connectors to everything, certifications, SaaS at a
  per-user price. Power BI is the reference our users know ([ARCHITECTURE](../architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer), section 11, maps every Power BI
  capability to a Drishti component). Looker's semantic model (LookML) and its Gemini conversational analytics; Superset and
  Metabase as open-source options.
- **Drishti's angle.** BI on the same governed data as the terminal, drilling from a chart into the record with its links and history;
  live as a native mode; row rules and masks applied before arithmetic and in every channel; reports as reviewable YAML with four-eyes
  publish; on-premises with no capacity licence.
- **Honest gaps.** No DAX, no Power Query UI, no custom-visual marketplace, no publish-to-web, no VertiPaq-class engine (DuckDB, rollups
  and caches instead), Excel integration behind; a fraction of their connectors and visuals.
- **Position.** Do not sell Drishti as "a cheaper Power BI". Sell it where the question is about entities, risk and explanation; leave
  enterprise BI where it is and feed it Drishti's published tables.

#### 4.3.2 Semantic and metrics layers: dbt Semantic Layer, Cube, AtScale

- **Facts.** dbt Labs open-sourced MetricFlow under Apache 2.0 (October 2025) and announced an all-stock merger with Fivetran (October 2025;
  reported complete, closing date **not verified**). Cube made its agentic analytics generally available in October 2025 and publishes tiers from USD 40 per
  developer a month (from a competitor's comparison; **not verified** on Cube's site). AtScale offers a universal semantic layer with
  native DAX and Tabular models for Power BI, an MCP server, and a December 2025 financing led by Snowflake Ventures.
- **Drishti's angle.** The pack is the semantic layer and more: kinds, meanings, units, links, roles, masks, contracts and screens in
  one place. A metrics layer gives numbers; a pack gives the numbers, the entities behind them and the screens.
- **Gap.** Drishti's models are consumed by Drishti; other BI tools cannot query them as a semantic layer except through its SQL
  endpoint (designed: the PostgreSQL wire). **Position: coexist**; read a bank's dbt models as datasets where they exist.

#### 4.3.3 Lakehouse and query engines: Databricks, Snowflake, Dremio, Starburst / Trino, DuckDB / MotherDuck, ClickHouse

- **Facts.** Databricks: AI/BI dashboards and Genie generally available; Unity Catalog metric views in preview from May 2025.
  Snowflake: Cortex Analyst with semantic models and semantic views; Streamlit in Snowflake. Starburst: managed Iceberg ("Icehouse")
  on Trino. DuckDB's DuckLake format reached 1.0 (April 2026) with MotherDuck support. ClickHouse raised USD 400 million and acquired
  Langfuse (January 2026). Dremio's 2025–26 announcements: **not verified**.
- **Drishti's angle.** Drishti is not a warehouse. It writes Delta and Iceberg itself, reads the bank's Databricks and Iceberg tables in
  place, and embeds DuckDB for BI. The pitch to a lakehouse customer is "the terminal, the risk engines and the explanations on top of
  the lake you already pay for".
- **Gap.** Their scale, governance catalogues and SaaS. **Position: complement**; a partnership or marketplace listing is a channel
  (5.3).

#### 4.3.4 Operational and real-time analytics: KX, Pinot, Druid, Rockset-class

- **Facts.** KX became a standalone company (March 2025), merged with OneMarketData (OneTick) and released a free KDB-X Community
  Edition (November 2025), and made KDB-X generally available (April 2026) with GPU acceleration (10–25x claimed). Pinot (StarTree)
  and Druid (Imply) serve user-facing real-time aggregations. Rockset was acquired by OpenAI in June 2024 and its service wound down.
- **Drishti's angle.** These are stores and engines; Drishti is the screen and the risk on top. A kdb+ tick store is a natural source
  for Drishti's live views (a connector is not built; REST or Kafka in between works today).
- **Position. Complement.** Never compete on tick-data query speed.

#### 4.3.5 Data-app builders: Palantir Foundry, Retool, Streamlit, Appsmith

- **Facts.** Foundry's ontology sits at the centre of Palantir's platforms with AIP for AI (from Palantir's 2025 annual report).
  Retool self-hosting needs its Enterprise plan; Appsmith's Community Edition is Apache 2.0, self-hosted and free (third-party
  comparisons; prices vary by source). Streamlit is part of Snowflake (Streamlit in Snowflake).
- **Drishti's angle.** Foundry is the closest concept: an ontology of business objects with screens over it. Drishti is far lighter
  (one server, YAML files, no forward-deployed engineers) and open in storage, with terminal UX and risk engines built in. Against
  Retool and Appsmith: a new product type gets a screen from a paragraph of YAML or from inference, not from a person dragging
  components; screens are linked, live and versioned.
- **Gap.** Foundry's scale of integration and its government and enterprise footprint; the app builders' freedom for any workflow.
- **Position.** Against Foundry: **compete** for mid-size organisations that cannot afford it; against Retool and Appsmith:
  **compete** for read-heavy, entity-centred internal tools; leave form-heavy workflow apps to them.

#### 4.3.6 Observability dashboards: Grafana

- **Facts.** Grafana OSS is AGPLv3 and free to self-host; Grafana Cloud has a free tier and paid tiers; Enterprise is quoted (third-party
  sources; Grafana's own price page not read).
- **Position. Coexist.** Grafana shows metrics and logs; Drishti shows business entities. Drishti's own health metrics can feed Grafana.

#### 4.3.7 AI analytics assistants: Copilot in Power BI, ThoughtSpot Spotter, Tableau Pulse, Looker

- **Facts.** Copilot in Power BI needs a paid Fabric capacity (F2 or higher, since April 2025) or Premium P1, and a tenant setting
  (third-party sources and a Microsoft Q&A answer; the Microsoft Learn page itself not read). ThoughtSpot launched Spotter in
  November 2024 and by late 2025 a family of agents (Spotter 3, SpotterViz, SpotterModel, SpotterCode) and industry agents including
  financial services. Tableau Pulse is included in some Tableau plans (sources conflict on which). Looker's conversational analytics
  uses Gemini over LookML, up to 5,000 rows a query.
- **Drishti's angle.** AI off by default; any of seventeen provider types including on-premises models ([AI](../guides/AI_GUIDE.md)); masks
  applied to prompts; numbers come from the engine, never from the model; a deterministic plain-words parser that needs no AI at all.
- **Gap.** Polish and breadth: their assistants are agents with planning; Drishti's AI answers single questions, with no memory of the
  last and no streaming (BI guide, "not built"). **Position.** Lead with control and determinism for regulated users; do not race on
  agent features.

### 4.4 Drishti's angle, in one list

- **Domain grammar packs**: an industry arrives as a pack, with meanings and contracts, not as a project.
- **One config-driven grammar across domains**: screens, BI items, editions and lenses are all YAML, reviewed and versioned.
- **Explainable figures**: provenance on every view, "how this number was built", playground workbooks, data contracts.
- **Lenses and editions**: one install, four focused products today (Risk, Trading, Market, World), white-labelled from configuration.
- **Open formats**: Delta and Iceberg, JSON Lines in, published tables out.
- **Light footprint**: one server and one console; SQLite by default, PostgreSQL for production.
- **Offline and on-premises**: vendored assets, no CDN, offline public-data snapshots.
- **Windows without an executable**: Java and Python only.

### 4.5 Honest gaps in general analytics

| Gap | Effect | Mitigation |
|---|---|---|
| Ecosystem size | no marketplace, partners or third-party content | lead with packs we write; pick one channel partner (5.3) |
| Connector breadth | 19 connector plugins against hundreds | JDBC, REST, files, S3 and Kafka cover most; the connector SPI is documented |
| Visual authoring depth vs Power BI | analysts will miss DAX, Power Query and custom visuals | position BI as "on the same data as the terminal", not as enterprise BI |
| No SaaS | trials need an install | the demo runs offline on a laptop; a hosted demo is a go-to-market option, not a product |
| Community | no forum, no open source, no users helping users | documentation depth (guides, 29 tutorials) partly substitutes |
| Certifications | SOC 2, ISO 27001 absent | on-premises only, until a company exists |
| Public data licences | some public-data content (IMF WEO, US equity prices) is internal-only | demo with licence-clean packs for external audiences |

---

## 5. Pricing, packaging and go-to-market

### 5.1 What competitors charge (only what is sourced)

| Product | Published price (list) | Source note |
|---|---|---|
| Power BI Pro | USD 14 per user a month (from USD 10, April 2025) | press and third-party; Microsoft announcement not read directly |
| Power BI Premium Per User | USD 24 per user a month (from USD 20, April 2025) | as above |
| Copilot in Power BI | requires a paid Fabric capacity F2+ or Premium P1+ (capacity price **not verified**) | Microsoft Q&A, third-party |
| Tableau Cloud, Standard | Creator USD 75, Explorer 42, Viewer 15 per user a month, billed annually | Tableau's pricing page |
| Tableau Cloud, Enterprise | Creator USD 115, Explorer 70, Viewer 35 per user a month | Tableau's pricing page |
| Cube | tiers from USD 40 per developer a month | a competitor's comparison; **not verified** |
| AtScale | quote only, priced on deployed semantic objects | a competitor's comparison |
| Appsmith | Community Edition free (Apache 2.0); Business about USD 15 per user a month | third-party; prices differ by source |
| Retool | per-user tiers; self-hosting on Enterprise only (quote) | third-party; prices differ by source |
| Grafana | OSS free (AGPLv3); Cloud Pro from about USD 19 a month plus usage; Enterprise quoted | third-party |
| KX KDB-X | Community Edition free | KX |
| Banking risk vendors (Murex, Calypso, FIS, MSCI, Bloomberg MARS, Numerix, Quantifi, Atoti, Opensee, AxiomSL, Regnology) | no public list prices | **not published**; enterprise licences and implementation projects |

Deal sizes for the banking vendors are known only from the transactions around them: Nasdaq paid about USD 10 billion for Adenza
(Calypso and AxiomSL); Regnology paid about EUR 450 million for Wolters Kluwer's FRR unit (2024 revenue EUR 123 million); the Finastra
TCM sale was estimated at about USD 2 billion including debt (an analyst estimate). These say the category is valuable and
consolidating; they do not give a price per seat or per position.

### 5.2 Observations for Drishti's packaging

- **Banking risk is sold as enterprise licences plus implementation**, not per user. Drishti's natural unit is the **edition and its
  scale** (Drishti Risk by positions, legal entities and nodes), with a pilot price for one department. A per-user price would
  undersell the risk engines and invite comparison with Power BI.
- **General analytics is sold per user or per capacity**, with free tiers. Drishti cannot win a per-seat price war and has no SaaS; for
  general analytics the offer is a site licence for on-premises use, priced against the internal tools team it replaces.
- **Editions are the packaging already built**: Risk, Trading, Market and World, each a configuration of one product. Planned lenses
  (Oversight, CRO, CIO) are natural premium tiers.
- **What not to do:** publish prices before a reference client exists; bundle pricing models we do not own.

### 5.3 Go-to-market implications

1. **Lead with a department, not the enterprise.** A market risk department with its own vectors; a four-to-six-week pilot on its own
   data and servers, judged on three things: any-scope answers, the day's change explained, and the validator's workbook.
2. **Partner with a pricing house.** Numerix, Quantifi or a mid-size platform vendor produces vectors and paths; Drishti aggregates,
   explains and shows them. A partner supplies credibility, support and the model-validation story Drishti lacks.
3. **Use ownership changes.** Finastra TCM's move to Apax, the Regnology roll-up, ActiveViam's move into pricing: clients reviewing
   their stack are listening.
4. **Lakehouse as a channel.** For banks on Databricks or an Iceberg lake, "risk and the terminal on your lake, no copy" is a short
   sentence; a marketplace listing is a later step.
5. **Build institutional proof in parallel with features.** A company, SOC 2 readiness, a validation pack per metric and a cluster
   benchmark at the client's size are as much on the critical path as FRTB.
6. **Outside banking, sell packs.** Each new industry is a pack with its own tutorial and data contract; lead with a pack that has
   licence-clean public data.

---

## 6. Risks to this analysis, and keeping it current

### 6.1 Risks

| Risk | Effect | Handling |
|---|---|---|
| Vendor material is marketing | competitor strengths may be overstated, weaknesses invisible | every competitor fact marked claimed; no competitor product tested |
| Ownership changes fast | Finastra TCM, Moody's regulatory reporting, dbt and Fivetran had unclosed or unconfirmed deals when read | dates of reading given; re-check every release |
| Our own ratings are judgements | a group rating hides strong and weak members | ratings by group are labelled typical; per-competitor text is the reference |
| Drishti's plan can change | the planned column may slip or change | planned items named by theme and called designs |
| Bias | written by the product's team | gaps listed as prominently as differentiators; an outside reviewer welcome |
| Measured numbers are from one workstation | a bank's hardware and data differ | measured, never extrapolated, here; the benchmark document states the machine |

### 6.2 Keeping it current

- **Owner:** the product owner.
- **When:** at every release, in the release's documentation pass, and whenever a competitor's ownership or product name changes.
- **How:** re-read each source in section 7, update the date read, move any planned item that shipped into the "today" column with a
  link to its guide, re-run the matrices, and record what changed in the CHANGELOG (never in this document).
- **Where it is shown:** Help, for administrators only (the help catalogue's `roles: [admin]`); not in any customer-facing index.

---

## 7. Sources

Every source read on **2026-11-01** unless noted. Claims are the publisher's own; we have not tested any product.

**Drishti (this repository)**

- [README](../../README.md); [IMPLEMENTATION_PLAN](../architecture/IMPLEMENTATION_PLAN.md) (release table and themed releases);
  [VAR_GUIDE](../guides/VAR_GUIDE.md); [EXPOSURE_GUIDE](../guides/EXPOSURE_GUIDE.md);
  [REGULATORY_VAR_REPORTING](../guides/REGULATORY_VAR_REPORTING.md); [REPORTS_GUIDE](../guides/REPORTS_GUIDE.md);
  [ARCHITECTURE](../architecture/ARCHITECTURE.md#28-business-intelligence-on-the-table-layer) (gap check against Power BI); [EDITIONS](../guides/ADMIN_GUIDE.md#12-what-a-lens-is);
  [MOBILE_DISPLAY](../guides/BUILD_GUIDE.md#32-designing-for-phones); [RESOURCES_AND_SCALE](../guides/CLUSTER_GUIDE.md#15-sizing-memory-cpu-and-disk);
  [BENCHMARK](../admin/BENCHMARK.md); [AI](../guides/AI_GUIDE.md); [lenses](../architecture/LENSES.md); [connectors](../guides/DATA_GUIDE.md#8-connectors).

**Risk aggregation cubes**

- [ActiveViam, Leonteq selects ActiveViam FRTB risk analytics (10 Feb 2026)](https://www.activeviam.com/updates/leonteq-selects-activeviam-frtb-risk-analytics)
- [ActiveViam FRTB solution documentation](https://docs.activeviam.com/solutions/frtb)
- [Nordic Capital, ActiveViam acquires Derivitec (May 2026)](https://www.nordiccapital.com/news-views/press-releases/activeviam-acquires-derivitec-to-strengthen-risk-analytics-capabilities/)
- [Choate, Guidepost Growth Equity leads sale of ActiveViam (2024)](https://www.choate.com/insights/guidepost-growth-equity-leads-sale-of-activeviam/)
- [Natixis selects Atoti (01net, Italian)](https://www.01net.it/natixis-seleziona-atoti-prodotto-di-punta-di-activeviam-come-soluzione-front-office-in-tempo-reale-per-rischio-e-pl/)
- [Crédit Agricole CIB taps Opensee (FinTech Futures)](https://www.fintechfutures.com/partnerships/cr-dit-agricole-cib-taps-opensee-for-market-risk-management-solution)
- [Opensee goes live at Société Générale (IBS Intelligence)](https://ibsintelligence.com/ibsi-news/opensees-big-data-analytics-platform-goes-live-at-societe-generale)
- [Opensee and Taula Capital (A-Team)](https://a-teaminsight.com/?p=71606)

**Trading platforms**

- [Murex, MX.3 enterprise risk and regulatory suite](https://murex.com/en/insights/mx3-enterprise-risk-and-regulatory-suite)
- [Murex, MX.3 XVA management](https://www.murex.com/en/insights/mx3-xva-management)
- [Murex, MX.3 for FRTB](https://www.murex.com/en/frtbsolution)
- [Nasdaq completes acquisition of Adenza (1 Nov 2023)](https://ir.nasdaq.com/node/106776)
- [Nasdaq 10-K FY2024 financial tables (Adenza consideration)](https://www.sec.gov/Archives/edgar/data/1120193/000112019325000008/Financial_Report.xlsx)
- [FIS Enterprise Risk Suite (formerly Adaptiv)](https://www.fisglobal.com/zh-cn/products/fis-enterprise-risk-suite2)
- [FIS XVA data services fact sheet](https://www.fisglobal.com/-/media/fisglobal/files/pdf/tip-sheet/xva-data-services-fact-sheet.pdf)
- [Risk.net, counterparty risk product of the year: FIS](https://www.risk.net/awards/6980176/counterparty-risk-product-of-the-year-fis)
- [FIS digital trading storefront (Finovate, Oct 2024)](https://finovate.com/fis-launches-digital-trading-storefront-to-upgrade-the-trading-experience/)
- [Finastra to sell TCM to Apax funds (Mondo Visione, May 2025)](https://mondovisione.com/media-and-resources/news/finastra-to-sell-treasury-and-capital-markets-division-to-apax-funds-2025519/)
- [Finastra sells TCM (Finovate)](https://finovate.com/finastra-sells-off-treasury-and-capital-markets-division/)
- [Banco Sofisa partners with Finastra (Vector Risk)](https://www.finastra.com/press-media/banco-sofisa-partners-finastra-elevate-its-risk-management-strategy)

**Enterprise market and credit risk**

- [MSCI RiskMetrics RiskManager](https://www.msci.com/data-and-analytics/risk-management-solutions/riskmetrics-riskmanager)
- [Bloomberg MARS](https://professional.bloomberg.com/products/risk/mars)
- [Bloomberg capital markets risk](https://professional.bloomberg.com/solutions/sales-trading/capital-markets-risk/)
- [Commercial Bank International adopts Bloomberg MARS](https://www.bloomberg.com/company/press/commercial-bank-international-adopts-bloomberg-mars-as-primary-risk-management-system)
- [Numerix (Wikipedia, ownership and acquisitions)](https://en.wikipedia.org/wiki/Numerix)
- [Quantifi XVA](https://www.quantifisolutions.com/xva/)
- [Quantifi named Chartis category leader](https://www.quantifisolutions.com/quantifi-earns-sell-side-market-risk-category-leader-title-from-chartis-research/)
- [Nordic Capital, Regnology to acquire Moody's Regulatory Reporting & ALM](https://www.nordiccapital.com/news-views/press-releases/regnology-backed-by-nordic-capital-signs-an-agreement-to-acquire-moody-s-regulatory-reporting-alm-solutions/)
- [A-Team, Regnology finalizes Moody's acquisition](https://a-teaminsight.com/briefs/regnology-finalizes-moodys-acquisition-as-reporting-and-balance-sheet-controls-converge/?brand=ati)
- [Moody's 10-K FY2025](https://www.sec.gov/Archives/edgar/data/1059556/000162828026009136/mco-20251231.htm)
- [S&P Global, FRTB modellability service](https://cdn.ihsmarkit.com/www/pdf/0523/FRTB-Modellability-service.pdf)
- [S&P Global, traded market risk case study](https://www.spglobal.com/marketintelligence/en/mi/topic/case-study-traded-market-risk.html)
- [SAS Risk Stratum](https://www.sas.com/en_us/software/risk-stratum.html)
- [SAS community, market risk module](https://communities.sas.com/t5/SAS-Software-for-Learning/MARKET-RISK-MODULE/td-p/811654)
- [Oracle Financial Services market risk data sheet](https://oracle.com/a/ocom/docs/industries/financial-services/ofs-market-risk-ds.pdf)
- [Oracle OFS MRMM Pack 8.0.5 release notes](https://docs.oracle.com/cd/E92094_01/PDF/8.0.5.0.0/OFS_MRMM_Pack_8.0.5.0.0_Release%20Notes.pdf)

**Regulatory reporting and governance**

- [Wolters Kluwer completes divestment of FRR (1 Dec 2025)](https://www.wolterskluwer.com/en/news/wolters-kluwer-completes-divestment-of-its-finance-risk-and-regulatory-reporting-unit)
- [Wolters Kluwer to divest FRR (21 Jul 2025)](https://www.wolterskluwer.com/en/news/wolters-kluwer-to-divest-its-finance-risk-and-regulatory-reporting-unit)
- [Khaitan & Co, FRR India divestment (OneSumX)](https://khaitanco.com/firm-matter/wolters-kluwer-groug-divestment-of-financial-risk-and-reporting-frr-business-by-wolters-kluwer-group-to-regnology)
- [IBM OpenPages](https://ibm.com/products/openpages)
- [IBM OpenPages 9.1.2 release (Sep 2025)](https://community.ibm.com/community/user/discussion/releases-september-26-2025-ibm-openpages-912-both-on-premise-and-cloud-hosted-is-now-available)

**Buy-side**

- [Deutsche Börse completes takeover of SimCorp (Q3 2023)](https://deutsche-boerse.com/dbg-en/media/press-releases/Q3-2023-Deutsche-B-rse-Group-reports-further-growth-and-completes-takeover-of-SimCorp-3711076)
- [Charles River and Axioma datasheet](https://crd.com/wp-content/uploads/2024/07/CRD-Axioma_Datasheet.pdf)
- [Institutional Investor, State Street ramps up competition with Aladdin](https://www.institutionalinvestor.com/article/2bswekxa78s7hfwc3t1j4/corner-office/state-street-ramps-up-competition-with-blackrocks-aladdin)

**General data and analytics**

- [The Register, Power BI price rises (Apr 2025)](https://www.theregister.com/2025/04/02/microsoft_power_bi_hikes/)
- [Microsoft Fabric community, Power BI pricing update](https://community.fabric.microsoft.com/t5/Power-BI-Updates-Blog/Important-update-to-Microsoft-Power-BI-pricing/ba-p/5174341)
- [Microsoft Q&A, Copilot availability with Fabric](https://learn.microsoft.com/en-us/answers/questions/5844828/question-about-copilot-availability-with-fabric-an)
- [Tableau Cloud pricing](https://www.tableau.com/pricing/cloud)
- [ThoughtSpot launches Spotter (Nov 2024)](https://www.thoughtspot.com/press-releases/thoughtspot-launches-spotter-the-autonomous-agent-for-analytics)
- [ThoughtSpot Spotter agents documentation](https://docs.thoughtspot.com/cloud/26.6.0.cl/spotter-agents)
- [Looker conversational analytics](https://cloud.google.com/looker/docs/studio/conversational-analytics-looker)
- [dbt Labs open-sources MetricFlow (Oct 2025)](https://tools.prnewswire.com/en-us/live/20813/release/20251014EN96968)
- [TechTarget, Fivetran and dbt Labs complete merger](https://www.techtarget.com/data-technologies/news/366643590/Fivetran-DBT-Labs-complete-merger-to-form-data-layer-for-AI)
- [Atlan, AtScale vs Cube](https://atlan.com/know/ai-agent/semantic-layer/atscale-vs-cube-semantic-layer/)
- [AtScale 2025 product announcements](https://www.atscale.com/blog/atscale-2025-product-announcements/)
- [Databricks AI/BI release notes 2025](https://learn.microsoft.com/en-us/Azure/databricks/ai-bi/release-notes/2025)
- [Databricks, AI/BI Genie generally available](https://www.databricks.com/en/blog/aibi-genie-now-generally-available)
- [Snowflake quickstart, Cortex Analyst semantic models](https://quickstarts.snowflake.com/guide/semantic_file_generation_for_cortex_analyst/index.html)
- [Starburst Icehouse (HPCwire, Apr 2024)](https://www.hpcwire.com/bigdatawire/2024/04/10/starburst-debuts-icehouse-its-managed-apache-iceberg-service/)
- [MotherDuck, managed DuckLakes](https://motherduck.com/blog/announcing-ducklake-support-motherduck-preview/)
- [MotherDuck, April 2026 product roundup (DuckLake 1.0)](https://motherduck.com/blog/april-2026-product-roundup.md)
- [ClickHouse raises USD 400M (Business Wire, Jan 2026)](https://businesswire.com/news/home/20260116008883/en/ClickHouse-Raises-$400M-Series-D-Led-by-Dragoneer-to-Accelerate-Expansion-Across-Analytics-and-AI-Infrastructure)
- [Langfuse joins ClickHouse](https://langfuse.com/blog/joining-clickhouse)
- [KX emerges as standalone company (Mar 2025)](https://www.fintechfutures.com/press-releases/kx-emerges-as-standalone-software-company-to-make-temporal-ai-a-commercial-reality)
- [KX KDB-X Community Edition (Nov 2025)](https://markets.financialcontent.com/winslow/article/bizwire-2025-11-19-kx-debuts-developer-built-kdb-x-community-edition-transforming-time-series-and-real-time-data-for-the-ai-era)
- [KX KDB-X general availability (Apr 2026)](https://www.businesswire.com/news/home/20260402375288/en/)
- [StarTree products](https://startree.ai/products/)
- [OpenAI acquires Rockset (InfoWorld)](https://www.infoworld.com/article/3715564/openai-acquires-rockset-to-enhance-chatgpt-real-time-data-processing.html)
- [Palantir FY2025 annual report](https://www.sec.gov/Archives/edgar/data/0001321655/000132165526000021/fy2025palantirars.pdf)
- [Superblocks, Appsmith pricing](https://www.superblocks.com/blog/appsmith-pricing)
- [Superblocks, Retool pricing](https://www.superblocks.com/blog/retool-pricing)
- [Grafana, pricing and cost models](https://grafana.com/docs/learning-hub/which-grafana/02-understand-your-options/08-pricing-and-cost-models/)

**Not verified (looked, not found or not read at source):** Opensee's storage format; whether RiskAuthority moved to Regnology; the
completion date of Regnology's Moody's purchase; completion and new name of Finastra TCM under Apax; the Fivetran and dbt Labs closing
date (reported complete; one source says 1 June 2026); Dremio and MetricStream 2025–26 announcements; Qlik's current AI offering and Talend integration;
Apache Superset, Preset and Metabase pricing; Cube's price on its own site; Fabric capacity prices; whether any competitor ships a
formula workbook that recomputes its figures; phone support across the banking vendors; Atoti's storage on open lake formats.

## Part II · Kernel and modules against plug-in platforms

> **Internal: not for customers.** This page names other vendors' products and gives our reading of their public material. Do not
> paste it into a deck, a proposal or an e-mail to a prospect.

**Status: analysis** (internal; owner: the product owner; every web source read on **2026-11-11**). It answers one question: **does any
commercial product have Drishti's micro-kernel plus module (plug-in) architecture for data, BI and risk, and how does Drishti compare?**
Vendor-by-vendor capability (VaR, FRTB, XVA, pricing, deal positioning) is in [Part I](#1-summary); Power BI
area by area is in [BI_AND_POWER_BI.md](BI_AND_POWER_BI.md). This page covers only the **architecture**: how each product is extended,
packaged and deployed. Every vendor statement is the vendor's own (we have tested none of these products); a statement we could not
source says **not verified**.

### Contents

1. [The answer in short](#l1-the-answer-in-short)
2. [Summary table](#l2-summary-table)
3. [Per product](#l3-per-product)
4. [Where Drishti is genuinely different](#l4-where-drishti-is-genuinely-different)
5. [Where Drishti is behind](#l5-where-drishti-is-behind)
6. [What to borrow (ideas only)](#l6-what-to-borrow-ideas-only)
7. [Sources](#l7-sources)

### L1. The answer in short

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

### L2. Summary table

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

### L3. Per product

#### L3.1 Risk aggregation: ActiveViam Atoti

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
- **Against Drishti.** Atoti is mature, certified for FRTB and has reference clients ([COMPETITIVE_ANALYSIS.md](#331-activeviam-atoti));
  Drishti differs in shape: domain-free kernel, modules loaded and removed at run time, one distribution file per product, open lake storage.

#### L3.2 Trading and risk platforms: Murex, Calypso, FIS, Numerix

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

#### L3.3 Hosted risk services: MSCI RiskManager, Bloomberg MARS

- **MSCI RiskMetrics RiskManager**: a hosted service on the RiskMetrics engine (VaR, factor, stress, liquidity, counterparty), with
  MSCI's data; results through a web application, reports and APIs [R1].
- **Bloomberg MARS**: Front Office, Valuations, Market Risk and other functions on Bloomberg's pricing library; reached through the
  Terminal or the MARS API (built on Bloomberg's server API and B-PIPE), also through BQL and BQNT or delivered to a private cloud [B1][B2].

Both are **services**, not platforms a client extends; the module question does not apply. They supply the data and the pricing Drishti
does not have.

#### L3.4 Plug-in platforms: the module model elsewhere

| Platform | Package | Install and upgrade | Trust | Lesson for Drishti |
|---|---|---|---|---|
| **Snowflake Native Apps** [S1] | application package: data, logic, **manifest**, **setup script** | setup script runs on install and upgrade; **versions and patches** rolled out gradually; Marketplace or private listing | the consumer **grants privileges** to the app after install | the consumer grants what a module may touch; staged rollout |
| **Salesforce 2GP and unlocked** [SF1][SF2] | metadata and code with an optional **namespace** | package versions; dependencies between packages | a namespaced unlocked package cannot depend on one without a namespace; two packages with the same component name cannot share an org | namespaces and dependency rules decided up front |
| **Splunk apps and add-ons** [SP1] | app package with a manifest (packaging toolkit) | Splunkbase or private upload | **AppInspect** checks every package; a pass is needed for Splunk Cloud | automated vetting before a module may load |
| **Elastic plugins** [E1][E2] | JVM plugin zip | **installed on every node**, each node restarted; most plugins must match the **exact** Elasticsearch version, a node refuses to start otherwise | plugins built by Elastic or third parties | the cost of exact-version binding; Drishti keeps it only for a first-party module that uses kernel internals |
| **Grafana plugins** [G1] | data source, panel or **app** (an app bundles data sources, visuals and dashboards) | plugin catalog | **five signature levels**: Grafana, Enterprise, Community, Commercial, Private | visible signature tiers |
| **Apache Superset** [SU1] | extensions: front-end and back-end core packages and a CLI | being built; the vendor page says the architecture is under active development | n/a | none yet |
| **Kubernetes operators** [K1] | custom resources plus a controller | deploy on demand, backups, upgrades with schema changes, leader election | cluster RBAC | the placement and singleton work is the same problem |

#### L3.5 BI tools: Power BI, Tableau, Qlik, Looker

All four extend a **closed BI core** with front-end pieces; none loads server-side domain logic as a package:

- **Power BI**: custom visuals and connectors; the full comparison is [BI_AND_POWER_BI.md](BI_AND_POWER_BI.md).
- **Tableau**: dashboard extensions and viz extensions, JavaScript with a manifest, served over HTTPS [T1].
- **Qlik Sense**: visualization extensions; the Extension API is legacy and nebula.js is recommended for new work; Qlik Cloud takes a zip
  of up to 30 MB [Q1][Q2].
- **Looker**: the Marketplace holds **blocks** (prebuilt LookML models with dashboards and Explores) and **applications** built on the
  extension framework, which an administrator must enable [L1]. LookML blocks are the nearest BI analogue of a Drishti module's packs.

### L4. Where Drishti is genuinely different

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

### L5. Where Drishti is behind

- **Maturity.** The kernel and module runtime are new; the Ed25519 publisher signature on a `.dmod` is designed and planned (j); until
  then a first-party module is verified by its id and content hash against the kernel's shipped list. The Console as a separate Spring Boot
  tier is planned (i4).
- **Ecosystem.** One substantial industry module (banking), no third-party module author, no marketplace or site repository (planned,
  phase P5), no partner network. Snowflake, Salesforce, Splunk and Grafana each run a public catalogue [S1][SP1][G1].
- **Support.** One author; no support organisation, no SLA, no 24x5 desk of the kind MSCI states [R1].
- **Certifications and proof.** No SOC 2 or ISO 27001, no ISDA certification for FRTB-SA or SIMM, no regulator acceptance, no reference
  client (details in [COMPETITIVE_ANALYSIS.md](#13-the-five-biggest-gaps)).
- **No SaaS.** FIS, Numerix, Murex and Calypso all offer a managed cloud service [F2][N2][M2][C3]; Drishti is self-hosted only.
- **No pricing or market data of its own.** MARS and RiskManager bring both [B1][R1]; Drishti reads what the client's engines produce.
- **Scale proof at cluster size** is limited ([BENCHMARK.md](../admin/BENCHMARK.md)).

### L6. What to borrow (ideas only)

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

### L7. Sources

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
