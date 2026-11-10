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
# Drishti distributions: Drishti Risk, Drishti Finance, Drishti Genomics, Drishti All

**Status: plan, not built (product owner, 2026-11-10).** Built in 2.9.11.i2 + i3 wave 3 (white label), on the distribution file of
[KERNEL_AND_MODULES.md section 6](KERNEL_AND_MODULES.md#6-the-distribution-file-white-label). One kernel build, one console build; each
distribution is a distribution file plus the modules it picks. The owner demos and tests **Drishti Risk** and **Drishti Finance**;
**Drishti Genomics** is the sample that proves the kernel carries no finance.

## 1. The three at a glance

| | **Drishti Risk** | **Drishti Finance** | **Drishti Genomics** (sample) |
|---|---|---|---|
| Id | `drishti.risk` | `drishti.finance` | `drishti.genomics` |
| For | traders, risk analysts, risk managers, middle office, model validation | investment and research analysts, economists, portfolio and fund analysts | research scientists, bioinformaticians (a demonstration) |
| Question it answers | "What is my risk, why did it move, am I within limits?" | "What are these companies, funds and economies doing, and what moves them?" | "What is in this cohort's data?" |
| Modules | `banking` (wave 2: one `.dmod`; later split into banking-core, var, exposure, frtb, simm), `market-data` | `capital-markets`, `economy`, `market-data` (no VaR or any risk number) | `genomics` (sample) |
| Lenses | **Risk** (default), Trading, Market | **Markets** (default), World | Research |
| Theme | navy, dark by default, terminal density | slate/teal, light by default, comfortable density | green, light |
| Data | trades, positions, scenario PVs, market data, limits, agreements (bank feeds, `.ready` files) | public data packs: US equities, funds, central banks, national statistics, OECD, World Bank, news, GDELT | a VCF connector (kernel extension) and sample cohorts |
| Engines on | VaR, sensitivities, FRTB-SA, SIMM, exposure, MR cube, OpenBLAS, pivot, data load | pivot, data load, memory lake (news), Flight | pivot, data load |

The kernel in every one: lakes and connectors, bindings, Rachana, Pivot and BI, Sutras, forms, data loads and the Inbox, Health,
Admin, Help, AI, Drishti Elements, the cluster.

## 2. Drishti Risk (traders and risk analysts)

**Functions (commands), all from the `banking` module:**

| Area | Commands |
|---|---|
| VaR and ES | VRES (VaR explorer, scoped tree, Why it moved, compare date), VTRD, VOFF, VLIM (limits), PORT (system and own baskets), POS, BANK, SCN, SSET, SPV, backtest, stressed VaR, parametric VaR, matured-items switch |
| P&L | PNL, PNLH, PNLV, PNLX, DPNL, FPNL, SSPNL, TCF |
| Sensitivities and market risk | SENS, RFAC, RTYP, RRAI, MRSK, MR cube, FXS, TVAL, STR |
| Regulatory capital | FRTB (SA: SBM, DRC, RRAO), DRCI, CAPI, regulatory reporting |
| Counterparty | EXP, XPATH, XRUN, XOFC, SACCR, SIMM, CCR, CVA, CSA, AGR, NSET, NSMTM, CPMTM, COLL, MC, CMP, LIM |
| Trading | TRD, CTRD, ITRD, LTRD, PTRD, TRDS, LEGS, SWAP, FUT, DEP, CSH, FLOWS, PAY, PAYM, EVT, EVTL, MAT, DLV, BAS, INV, PROD, IDX, DES |
| Market data (the shared `market-data` module, also in Finance) | MKT, CRV, IRV, SURF, FX, FXF, FXV, EQ, EQV, EQB, EQX, CMD, CMDC, CMDV, INF, INFC, BND, CDS, REPO, DIV, CORR, CORRC, CPV, FIX, OHLC |
| Playgrounds | PLAY VAR, PLAY FRTB, PLAY SIMM, exposure playground (k) |

**Screens:** Home "Risk today" (bank VaR and ES, limit board, biggest movers, breaches, data health); VaR explorer; Why it moved
(layout A); MR cube; P&L explain; FRTB, SIMM, SA-CCR, exposure and CCR screens; limits board; trade and position screens; market data
screens; playgrounds; Pivot and BI with the risk presets; Admin → Data loads, Upload (`.ready`), Data bindings, Engines, Cluster.

**Backend:** the `banking` module's compute (VaR, ES, Euler components, parametric, backtest, FRTB-SA, SIMM, SA-CCR, exposure, CVA),
its KindProviders (positions, system baskets), the VaR hierarchy from promoted columns, the scenario-PV intake at position grain; the
kernel's engines switched on as listed; cluster placement: VaR, FRTB and exposure compute on the heavy nodes, pages and API on all.

**Bindings:** trade, position, scenario-pv, scenario-set, fx-scenario, pnl-vector, var-limit, market data kinds, netting-set, csa,
agreement → the bank's lake (production) or the demo risk lake.

## 3. Drishti Finance (investment, research and economics)

**Functions (commands):**

| Area | Commands | Module |
|---|---|---|
| Companies | USEQ, USEQS, SIC, FACT, FILING, EDGAR, HLD, PXH, IDX, ETF | capital-markets |
| Funds | FUND, FUNDS, FND, HELD, TOP, TRACK, TRK, VIEW | capital-markets |
| Economies | WRLD, ECON, MACRO, FCST, FISC, LABR, ELEC, SOCI, REGN, REGE, JUR, OFFC, PARTY, ECG | economy |
| Central banks and statistics | CBANK, CBNK, CBSER, SER, NSTAT, STCAN, OECD, OECDS, OECDP, MEAS, WDI, WDIS, WDEV, IND | economy |
| News and events | NEWS, NWMK, QUOTE, TOPIC, CO, ONEWS, PRESS, PREL, LIST, GDELT, GDLT | economy |

**Screens:** Home "Markets and economy" (indices, movers, central-bank decisions, news); company and fund pages; the world map and
country pages; series charts; Pivot and BI over fundamentals and series; Admin → External data (the refresh scripts).

**Backend:** the `capital-markets` and `economy` modules (today packs and the economy console plugin; packaged as modules in wave 2
next to banking, mostly content: kinds, Sutras, pages), the public-data refresh scripts, the memory lake for news, Flight for data out.

**Cautions carried over:** IMF data and US equity prices stay internal-only (off by default in this distribution's `demo`), as today.

## 4. Drishti Genomics (sample)

A module built from the Module SDK alone: a VCF reader as a kernel connector extension; kinds `variant`, `gene`, `sample`, `cohort`;
commands VAR-free (`VARIANT`, `GENE`, `COHORT`); one page folder; synthetic data. Purpose: the release's proof that the kernel boots and
is useful with no finance at all. Not for the owner's demos.

## 5. What is shared, and what is not

- **One kernel build and one console build.** A distribution never forks code: it is a file (brand, theme, lenses, Home, menus, docs,
  features, default bindings, demo) plus the modules it lists.
- **Help per distribution:** each shows only its modules' guides and tutorials, titled "Drishti Risk Help" etc.; kernel guides in all.
- **Commands not in a distribution's modules do not exist there** (not greyed out): they are not in the command bar, menus, Help or AI.
- **Both on one estate (the owner's demo):** one kernel cluster with all three finance modules placed, and **two consoles**:
  Drishti Risk on 17480 and Drishti Finance on 17481, each showing the intersection of its distribution and the kernels' modules
  (KERNEL_AND_MODULES 10.6). Or two separate stacks; the file decides.

## 6. Drishti All

`drishti.all`: **every module, every lens, every engine**, the neutral Drishti brand. It makes sense as:

- **the default when no distribution file is given**, so today's installs and everyone's habits keep working unchanged;
- **the developer and QA distribution**: the drill, the page walk and the tutorials run on it, so one run covers every module;
- **the demo switcher**: one stack where the lens picker moves between Risk, Markets and World.

It is not a product to sell (too broad for any one user community), so customer installs pick Risk or Finance. Genomics stays out of
All by default (a sample), available with `--modules +genomics`.

## 7. Starting a particular distribution

The distribution is chosen **at start, by one argument or one setting**; nothing else changes.

| How | Command |
|---|---|
| One node, Linux and macOS | `bin/start-all.sh --distribution risk` (or `finance`, `genomics`, `all`) |
| One node, Windows | `bin\start-all.ps1 -Distribution risk` (or `bin\start-all.cmd --distribution risk`) |
| A cluster | `bin/start-cluster.sh --cluster drishti-stack/stack.yaml` with `distribution: risk` in the stack file (per console, so one cluster can serve a Risk console and a Finance console) |
| Any start, by setting | `DRISHTI_DISTRIBUTION=risk` or `drishti.distribution: risk` in `config/drishti.yaml`; a path instead of a name loads a custom distribution file |
| IntelliJ / PyCharm | the run configurations gain `Drishti server (Risk)`, `(Finance)`, `(All)` and the console's `--distribution` |

`bin/status.sh` and Admin → White label show the running distribution; Health says which modules it asked for and which loaded. A short
name maps to `config/distributions/drishti.<name>.yaml`; a site may add its own file there (its own brand) without touching the shipped ones.

**The owner's stack (recommended):** one 3-node cluster with every finance module placed; two consoles from one stack file:

```yaml
consoles:
  - { name: risk,    port: 17480, distribution: risk }
  - { name: finance, port: 17481, distribution: finance }
```

`bin/start-cluster.sh --cluster drishti-stack/stack.yaml` then starts both: Drishti Risk on http://<host>:17480 and Drishti Finance on
http://<host>:17481. The Deployment and QA guide gets a step-by-step section for each, Linux, macOS and Windows, with what you should see.

## 8. Build plan inside 2.9.11.i2 + i3

| Wave | Item |
|---|---|
| 2 | `banking` as a `.dmod` (in progress); **`market-data` split out of banking as its own module**; **`capital-markets` and `economy` packaged as modules** (added for these distributions) |
| 3 | the distribution file reader (kernel and console), `drishti.risk.yaml`, `drishti.finance.yaml`, `drishti.genomics.yaml`, `drishti.all.yaml` (the default); `--distribution` on every bin/ script and stack file consoles; Home, menus, Help and AI filtered by distribution; themes; Admin → White label (option A); the old product keys read once |
| 4 | proofs: each distribution boots from its file alone; the owner's stack runs Risk on 17480 and Finance on 17481 over one cluster; genomics boots with no finance |

## 9. Decisions (product owner, 2026-11-10)

1. **Drishti Finance shows no VaR** or any other risk number; every risk number stays in Drishti Risk.
2. **Market data screens are in both** Drishti Risk and Drishti Finance (curves, FX, quotes, surfaces): they move out of `banking` into
   a shared **`market-data` module** that both distributions list.
3. **The owner's demo: two consoles over one cluster** (Risk on 17480, Finance on 17481), from one stack file.
4. **Drishti All is the default** when no distribution is named.
