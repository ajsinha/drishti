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

# Market risk in release i6: the detailed design

**Status: design, nothing built.** The scope the owner agreed on 2026-11-15: the VaR engine extended with decomposition,
liquidity-adjusted VaR and concentration risk, and six engines: P&L explain, stress testing, FRTB-SA (completed), FRTB-IMA, the
default risk charge and SIMM. Coding starts when the engine lookup of i5 has shipped and the owner has answered the questions of
[section 21](#21-open-questions-for-the-owner).

**The hard constraint: Drishti never prices.** There is no quant or pricing library. Every present value, scenario value,
sensitivity, hypothetical and actual P&L is supplied by the bank's systems. Drishti aggregates, ranks, and does the statistics and
simulation that need no pricing: quantiles and expected shortfall, correlations, rank tests, covariance, and Monte Carlo of defaults
from supplied jump-to-default amounts. Where a figure would need a price the bank did not send, the answer says so and names what
is missing; it is never estimated silently.

It does not repeat its neighbours; each fact has one home:

| For | Read |
|---|---|
| An engine's anatomy (mathematics, service, manifest, settings, pack with a reactive connector, module) | [ENGINES_IN_THE_CLUSTER.md](ENGINES_IN_THE_CLUSTER.md) |
| The engine lookup, contracts of plain records, placement per engine, resources, end of day in `needs` order | [CLUSTER_I5_DESIGN.md sections 5 to 7](CLUSTER_I5_DESIGN.md#5-engines-local-or-remote-the-one-lookup) |
| The shared rules of every data contract (keys, dates, signs, vectors, delivery, official results) | [contracts/README.md](../contracts/README.md) |
| Every field of every input named here, with a JSON line | [contracts/packs/market-risk.md](../contracts/packs/market-risk.md#planned-inputs) (generated from the contract files) |
| VaR, FRTB-SA and SIMM as built today | [VAR_GUIDE.md](../guides/VAR_GUIDE.md), [FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md), [SIMM_GUIDE.md](../guides/SIMM_GUIDE.md) |
| The documentation the release ships (one master page, a page per engine) | [market-risk-i6/README.md](market-risk-i6/README.md), the skeleton |
| The screens to choose | [mockups/market-risk-i6/README.md](mockups/market-risk-i6/README.md) |

Regulatory references are to the Basel framework's chapters (MAR21 to MAR23 the standardised approach, MAR31 model eligibility of
risk factors, MAR32 backtesting and P&L attribution, MAR33 the internal models capital). Every threshold, weight and correlation
lives in a parameter file per regime, never in code; the paragraph numbers are confirmed against the text when each parameter file
is written ([risk R3](#20-risks)).

## Contents

1. [What changes, on one page](#1-what-changes-on-one-page)
2. [Engine boundaries and the needs and uses graph](#2-engine-boundaries-and-the-needs-and-uses-graph)
3. [Shared inputs, loaded once](#3-shared-inputs-loaded-once)
4. [Contracts between engines: how FRTB-SA and SIMM stop holding VarService](#4-contracts-between-engines-how-frtb-sa-and-simm-stop-holding-varservice)
5. [The VaR engine extended: decomposition, liquidity-adjusted VaR, concentration](#5-the-var-engine-extended-decomposition-liquidity-adjusted-var-concentration)
6. [P&L explain](#6-pl-explain)
7. [Stress testing](#7-stress-testing)
8. [FRTB-SA, completed](#8-frtb-sa-completed)
9. [FRTB-IMA](#9-frtb-ima)
10. [The default risk charge by simulation](#10-the-default-risk-charge-by-simulation)
11. [SIMM, completed](#11-simm-completed)
12. [What every engine ships](#12-what-every-engine-ships)
13. [Data the bank must supply, and its size](#13-data-the-bank-must-supply-and-its-size)
14. [Performance targets and placement in the cluster](#14-performance-targets-and-placement-in-the-cluster)
15. [Accuracy: nothing stale, nothing empty, nothing silent](#15-accuracy-nothing-stale-nothing-empty-nothing-silent)
16. [Test strategy and the hand-computed goldens](#16-test-strategy-and-the-hand-computed-goldens)
17. [Documentation](#17-documentation)
18. [Screens](#18-screens)
19. [Build plan](#19-build-plan)
20. [Risks](#20-risks)
21. [Open questions for the owner](#21-open-questions-for-the-owner)

## 1. What changes, on one page

| Today | In i6 |
|---|---|
| VaR answers component, incremental and marginal VaR on its pages | The same figures become a computed kind, a BI table and an official record at chosen levels; liquidity-adjusted VaR and concentration measures are added to the VaR engine |
| P&L explain is a stored kind (`pnl-explain`) of supplied figures; the backtest (built, 99% and 97.5%, bank to desk) compares desk P&L with the official VaR | A **P&L explain engine**: hypothetical against risk-theoretical P&L per position, the unexplained P&L, the attribution to risk factors from supplied sensitivities and moves, the P&L attribution test; backtesting extended to the desk rule |
| Stress results are stored kinds (`stress-scenario`, `stress-result`) | A **stress engine**: the bank's scenario values summed to any scope, the matrix, limits, a sensitivity-based estimate for scenarios without values, reverse stress, regulatory programmes |
| FRTB-SA computes on request; no computed kind, no official record; it holds `VarService` | **Completed**: a computed kind through a reactive connector, official records, the default risk and residual inputs live, runs per desk set, scopes by contract |
| No internal models capital | A **FRTB-IMA engine**: expected shortfall by liquidity horizon from partial-shock scenario values, the eligibility test and the stress add-on for non-modellable factors, desk zones, capital aggregation |
| The standardised default risk charge inside FRTB-SA | A **DRC engine**: a two-factor default simulation from supplied jump-to-default, LGD and PD |
| SIMM computes per netting set on request; it holds `VarService` | **Completed**: contributions, the CRIF export, official records, scopes by contract |
| Engines of one module share a node because they share service classes | Every engine reaches another only through a contract interface and the engine lookup, so each is placed on its own |

Nine planned input contracts are written and one is revised ([section 3](#3-shared-inputs-loaded-once)). Seven screens are new;
three have a mock-up to choose from ([section 18](#18-screens)).

## 2. Engine boundaries and the needs and uses graph

### 2.1 The engines

| Engine (`id`) | Owns | Does not own |
|---|---|---|
| `var` (extended) | scenario values and P&L vectors of the VaR sets, positions, hierarchies, baskets and scope resolution, VaR, ES, stressed VaR, decomposition, liquidity-adjusted VaR, concentration, the VaR series and the backtest count | desk P&L feeds' attribution, stress scenarios, capital |
| `sensitivities` (as today) | position sensitivities and the factor dictionary: load, checks, sums by factor and bucket | any capital formula |
| `pnl-explain` (new) | hypothetical and realised-move P&L per position, their sums, the unexplained P&L, the factor attribution, the P&L attribution test, the desk view | the VaR series (asked of `var`), sensitivities (asked of `sensitivities`) |
| `stress` (new) | scenario definitions, the stress-test scenario set's values, the matrix, stress limits, the sensitivity-based estimate, reverse stress | the VaR sets |
| `frtb-sa` (completed) | the sensitivities-based method, the standardised default risk charge, the residual add-on, runs per desk set | sensitivities' load |
| `frtb-ima` (new) | partial-shock scenario values, expected shortfall by liquidity horizon, the eligibility test, the stress add-on, desk zones, capital aggregation | the attribution test (asked of `pnl-explain`), the backtest (asked of `var`), the standardised charge (asked of `frtb-sa`), the simulated default charge (asked of `drc`) |
| `drc` (new) | issuer parameters, the default simulation, its contributions, the weekly record | the standardised default charge (stays in `frtb-sa`; both read `default-risk`) |
| `simm` (completed) | ISDA SIMM per netting set and regime, contributions, the CRIF export and comparison | sensitivities' load |
| `mr-cube` (as today) | the market-risk cube on the pivot engine | it gains `uses` of the new engines' tables |

### 2.2 The graph

```
                      ┌──────────────┐      ┌───────────────┐
                      │     var      │      │ sensitivities │
                      └──┬───┬────┬──┘      └──┬────┬────┬──┘
           needs ────────┘   :    :            │    │    :
      ┌────────────┐         :    :            │    │    :   ( : = uses, │ = needs )
      │ pnl-explain│<········:····:············:····:····:
      └─────┬──────┘         :    :            │    │
            │         ┌──────┴┐  ┌┴───────┐  ┌─┴────┴─┐  ┌──────┐
            │         │ stress│  │  drc   │  │frtb-sa │  │ simm │──uses── exposure
            │         └───────┘  └───┬────┘  └───┬────┘  └──────┘
            │                        │           │
            └──────────────┐   ┌─────┘   ┌───────┘
                         ┌─┴───┴─────────┴─┐
                         │    frtb-ima     │
                         └─────────────────┘
```

| Engine | needs | uses | Why |
|---|---|---|---|
| `var` | none | none | unchanged |
| `sensitivities` | none | none | unchanged |
| `pnl-explain` | `var` | `sensitivities` | the VaR of the day before and the backtest count come from `var`; the factor attribution is added when sensitivities are reachable |
| `stress` | none | `var`, `sensitivities` | summing supplied scenario values needs neither; scopes beyond its own columns ask `var`; the sensitivity-based estimate and reverse stress ask `sensitivities` |
| `frtb-sa` | `sensitivities` | `var` | **was `needs: [var, sensitivities]`**: scopes its own rows cannot resolve, and notionals for the residual add-on, ask `var` when it is reachable |
| `drc` | none | `var` | scopes beyond its own columns |
| `simm` | `sensitivities` | `exposure`, `var` | as today, with `var` as a use |
| `frtb-ima` | `pnl-explain`, `frtb-sa`, `drc` | `var` | a desk's zone needs the attribution test and the backtest; the capital formula needs the standardised charge of the same desks and the default charge |
| `mr-cube` | `var`, `pivot` | `sensitivities`, `frtb-sa`, `stress`, `pnl-explain`, `frtb-ima` | more tables when present |

The graph has no cycle. `frtb-ima` reaches `var` through `pnl-explain` as well as directly; it asks each engine only for what
that engine owns.

**End of day** runs official captures in this order, wherever the engines are placed
([CLUSTER_I5_DESIGN.md section 7.3](CLUSTER_I5_DESIGN.md#73-end-of-day-in-needs-order-wherever-the-engines-are)): `var` and
`sensitivities`; then `pnl-explain`, `stress`, `frtb-sa`, `simm`, `drc` (weekly, [section 10](#10-the-default-risk-charge-by-simulation));
then `frtb-ima`. An engine whose need has no official record for the date does not capture; its record says *waiting for the
official VaR of 2026-09-30*, never a figure built on an unofficial one.

### 2.3 Where the code lives

All in the `banking` module, following the recipe of
[ENGINES_IN_THE_CLUSTER.md section 1](ENGINES_IN_THE_CLUSTER.md#adding-a-compute-model-the-recipe-frtb-and-simm-follow-it-in-i6):

| Layer | New in i6 |
|---|---|
| Mathematics (`banking-compute`) | `stats/` (one home for rank, quantile, expected shortfall, Spearman, Kolmogorov-Smirnov, covariance: the VaR engine's existing rank and ES rules move here unchanged and VaR calls them); `var/` additions (liquidity, concentration); `pnl/`; `stress/`; `ima/`; `drc/` (jump-to-default netting shared by the standardised charge and the simulation; the simulation); `frtb/` and `simm/` as they are |
| Contracts (`banking-api`) | the interfaces and records of [section 4](#4-contracts-between-engines-how-frtb-sa-and-simm-stop-holding-varservice) |
| Service (`banking-service`) | `pnl/`, `stress/`, `ima/`, `drc/`; `frtb/` and `simm/` lose their imports of `var/` |
| Manifest | five new entries in `META-INF/drishti/engines.yaml`, each with `contract`, `locality`, `resources`, `timeouts` and `official` as i5 defines them |
| Settings | `config/engines/pnl-explain.yaml`, `stress.yaml`, `frtb-ima.yaml`, `drc.yaml`; additions to `var.yaml`, `frtb-sa.yaml`, `simm.yaml`. Every path in them is absolute or anchored to a named root (`${module.dir}`, `${lake.root}`, `${drishti.data.dir}`) |
| Pack (`market-risk`; SIMM's kinds in `counterparty-risk`) | the input contracts, the computed kinds, a reactive connector per engine (`pnl-engine`, `stress-engine`, `frtb-sa-engine`, `frtb-ima-engine`, `drc-engine`, `simm-engine`), command words |
| Parameters | `capital/frtb-ima.yaml` (thresholds, horizons, correlations, multipliers) and `capital/drc-ima.yaml` beside the standardised files, one per regime |

**One vector store.** The stress engine and the FRTB-IMA engine hold scenario vectors per position, as VaR does. They use the VaR
engine's store classes (chunked columns, the mapped hot file, per-node sums, the float32 hot copy with float64 sums) as a library
from `banking-compute`, each with its own instance and budget. There is one implementation; no engine writes a second.

## 3. Shared inputs, loaded once

**The rule.** Each input table is loaded by exactly one engine, on the nodes where that engine is placed. Another engine that
needs something from it asks the owner through its contract for the small answer (a sum, a series, a membership), never for the
rows, and never loads the table a second time. Every node reads the same named lakes, so "loaded once" also means one copy on
disk.

| Input (command) | Contract | Status | Loaded by | Also used by (through a contract) |
|---|---|---|---|---|
| Scenario PVs, P&L vectors of the VaR sets (SPV, PNLV) | [scenario-pv](../contracts/packs/market-risk.md), pnl-vector | active | `var` | `pnl-explain` (the approximate RTPL, the day's quantile), `stress` (a scenario date of the window as a historical scenario) |
| Scenario PVs of the stress-test set (SPV, set type `STRESS_TEST`) | scenario-pv; scenario-set 1.2 adds `SCENARIO_IDS` and the set types ([7.2](#72-how-the-scenario-values-ride-in-scenario-pv)) | planned addition | `stress` | `frtb-ima` (none), `mr-cube` |
| Scenario FX, scenario sets (FXS, SSET) | fx-scenario, scenario-set | active | `var`; `stress` and `frtb-ima` read their own sets' rows | |
| Desk P&L, actual and hypothetical (DPNL) | desk-pnl | active | `var` (the backtest, as today) | `pnl-explain` (the desk view, the reconciliation) |
| **Hypothetical P&L per position (HPL)** | hypothetical-pnl | **planned, new** | `pnl-explain` | `frtb-ima` (through the attribution test) |
| **Realised-move PV per position (RPV)** | realised-pv | **planned, new** | `pnl-explain` | the same |
| Position sensitivities, factor dictionary (SENS, RFAC) | position-sensitivity, risk-factor | active | `sensitivities` | `frtb-sa`, `simm`, `pnl-explain`, `stress`, `frtb-ima` (which factors a desk depends on) |
| **Market moves with the series mapping (MMOV)** | market-move | **planned, new** | `sensitivities` (it owns the factor dictionary the moves are checked against) | `pnl-explain` (attribution), `stress` (covariance for reverse stress) |
| **Real-price observations (RFOB)** | risk-factor-observation | **planned, new** | `frtb-ima` | |
| **Partial-shock scenario PVs (ESPV)** | es-scenario-pv | **planned, new** | `frtb-ima` | |
| **Stress P&L of non-modellable factors (NMRF)** | nmrf-stress | **planned, new** | `frtb-ima` | |
| Default risk inputs (DRCI) | default-risk **1.1** (adds `JTD`, `LGD`, `INSTRUMENT_CLASS`, the scope columns) | planned, revised | `frtb-sa` (the standardised charge) and `drc` (the simulation) each read the fields they own; the netting is one library | |
| **Issuer default parameters (IDEF)** | issuer-default | **planned, new** | `drc` | |
| **Stress scenario definitions (SDEF)** | stress-definition | **planned, new** | `stress` | |
| **Market liquidity (MLIQ)** | market-liquidity | **planned, new** | `var` | |
| Residual risk inputs (RRAI), regulatory desks (RDSK), capital inputs (CAPI) | residual-risk, regulatory-desk, capital-input | planned (exist) | `frtb-sa`; `pnl-explain` and `frtb-ima` (desks); `frtb-ima` (supervisory multiplier override) | |
| VaR limits (VLIM) | var-limit | active | `var` | stress limits: [question Q9](#21-open-questions-for-the-owner) |

Two inputs the brief named need no new contract:

- **Actual P&L per desk** is the active `desk-pnl` (`ACTUAL_PNL`, `HYPOTHETICAL_PNL`). One additive field is proposed for its
  version 1.1: `REGULATORY_DESK_ID`, optional, so a regulatory desk that is not a management desk has its own row.
- **Sensitivities aligned with CRIF** are the active `position-sensitivity` with the factor dictionary's SIMM columns; the mapping
  to and from ISDA CRIF is [SIMM.md section 3.2](../contracts/SIMM.md). The counterparty's CRIF (`crif-external`) stays the draft
  of SIMM.md section 3.4 and becomes a planned contract with the comparison step ([section 11](#11-simm-completed)).

`default-risk` is read by two engines. This is the one exception to "one loader", and it is deliberate: the table is small
([section 13](#13-data-the-bank-must-supply-and-its-size)), the two charges are separate regulatory figures that must each stand
when the other engine is off, and both use the same loading and netting classes.

## 4. Contracts between engines: how FRTB-SA and SIMM stop holding VarService

### 4.1 What they hold today

`FrtbService` and `SimmService` take `VarService` for one thing: `scopeMembers(principal, scope, date)`, which returns the trades
and positions of a scope as the person may see them, with notionals and a count of hidden rows. `FrtbService.Scopes` already
abstracts it as a functional interface and already has a fallback ("null: the records' own attributes"). That is why the three
engines must share a node, and why FRTB-SA `needs` VaR.

### 4.2 The design

1. **The scope grammar becomes a library, run by each engine on its own rows.** `ScopeExpr` (the parser) and the resolver move to
   `banking-api` as pure code over a small interface, `ScopeIndex` (ids and promoted columns, no vectors). Each engine builds its
   index from the rows it loaded: the sensitivities carry `LEGAL_ENTITY`, `DIVISION`, `DESK`, `TT_BOOK`, `LIMIT_LETTER`, product and
   ids, as every new position-grain contract does. `BANK`, `DESK:`, `BOOK:`, `LE:`, `POS:`, `TRD:` and `WHERE:` over those columns
   resolve locally, with the person's row rules applied by the same entitlement service on the engine's own node. There is one
   resolver; what differs is the rows it runs on.
2. **Baskets and captures are Drishti-owned content**, in the database and content store every node reaches. The library reads
   them through the kernel's store interface, so `BASKET:` and `CAPTURE:` also resolve locally.
3. **Only what the engine's rows cannot answer asks VaR**, through a contract and the lookup: a `WHERE:` on a column the engine's
   rows lack (for example `COUNTERPARTY` on sensitivities), and notionals for the residual add-on when no `residual-risk` input is
   served. If VaR is not reachable, the answer is a refusal in words naming the column and the engine
   (*this scope uses COUNTERPARTY, which the sensitivities do not carry; VaR, which can resolve it, is not reachable right now*),
   never a wider or narrower scope than asked.
4. **So `var` moves from `needs` to `uses`** for `frtb-sa` and `simm`. A site may run FRTB-SA with VaR switched off.

### 4.3 The interfaces

In `com.ash.drishti.banking.api`, records of primitives, strings, dates, enums, lists and maps only, a leading `Principal`, as
[CLUSTER_I5_DESIGN.md section 5.1](CLUSTER_I5_DESIGN.md#51-the-contract-an-engine-offers-to-other-code) requires. Bulk never
crosses in a record: a large membership comes back as a ticket for Arrow Flight.

```java
public interface VarContract {                                  // offered by var; version 1 in i5, these methods added in i6
    ScopeMembers members(Principal p, String scope, LocalDate date, int inlineLimit);
    VarFigures figures(Principal p, String scope, LocalDate date, VarAsk ask);          // VaR, ES at the confidences asked
    ScopeVector vector(Principal p, String scope, LocalDate date, String setType);      // one P&L per scenario: at most the window
    List<OfficialVar> official(Principal p, String nodeId, LocalDate from, LocalDate to);
    BacktestCount backtest(Principal p, String nodeId, LocalDate date, BacktestAsk ask); // exceptions against APL and HPL
}
public record ScopeMembers(LocalDate date, String canonical, long trades, long positions, int hidden, String digest,
                           List<String> positionIds,            // filled when positions <= inlineLimit, else empty
                           String flightTicket,                 // else: the ids and notionals as a stream
                           Map<String, Double> notionals) {}    // inline only

public interface SensitivityContract {                          // offered by sensitivities
    FactorSums sums(Principal p, String scope, LocalDate date, SumBy by);               // by factor, bucket or risk class
    FactorMoves moves(Principal p, LocalDate date, List<String> riskFactors);           // the market-move rows of the date
    Covariance covariance(Principal p, LocalDate date, List<String> riskFactors, int window);
}
public interface PnlContract {                                  // offered by pnl-explain
    PnlFigures figures(Principal p, String scope, LocalDate date);                      // APL, HPL, RTPL, unexplained, completeness
    PlaResult attributionTest(Principal p, String regulatoryDesk, LocalDate date);      // Spearman, KS, zone, days used
}
public interface FrtbSaContract {                               // offered by frtb-sa
    SaCharge charge(Principal p, String scope, LocalDate date);                         // SBM, DRC, RRAO, total; official or not
    SaCharge chargeOfDesks(Principal p, List<String> regulatoryDesks, LocalDate date);  // one run over a set of desks
}
public interface DrcContract {                                  // offered by drc
    DrcFigures latest(Principal p, String scope, LocalDate date);                       // the newest weekly figure and the 12-week mean
}
public interface StressContract { StressMatrix matrix(Principal p, String scope, LocalDate date, String scenarioSet); }
public interface FrtbImaContract { ImaCapital capital(Principal p, String scope, LocalDate date); }
public interface SimmContract { SimmMargin margin(Principal p, String nettingSet, String regime, LocalDate date); }
```

Every answer record carries `official` (true when read from the official record of the date, false when computed now) and the
i5 `Provenance`, so a dependent engine can refuse to build an official figure on an unofficial one.

### 4.4 What is deleted

`FrtbConfiguration` and `SimmConfiguration` lose `ObjectProvider<VarService>`; `FrtbService.Scopes` is replaced by the library's
resolver; `VarService.ScopeMembers` becomes the contract record. The conformance check of i5 (no import of another engine's
service package) then passes for banking without an exception. Nothing is kept beside the new way.

## 5. The VaR engine extended: decomposition, liquidity-adjusted VaR, concentration

These are parts of the `var` engine, not engines of their own: each is a different reading of the vectors VaR already holds.

### 5.1 Inputs

The date's vectors (as today), and for liquidity and concentration: `market-liquidity` (new), the positions' `QUANTITY` and
`BASE_PV` (already on scenario-pv), and for issuer concentration the `OBLIGOR` of `default-risk` where served.

### 5.2 Decomposition

For a scope with total vector `t(s) = Σ_i p_i(s)` over its parts `i` (positions, or any grouping of them), `N` scenarios,
`k = ⌈N(1 − α)⌉`, and `r(1) … r(N)` the scenarios ordered from the worst total:

| Figure | Formula | Status |
|---|---|---|
| VaR | `VaR = −t(r(k))` | built |
| Component VaR | `c_i = −mean of p_i(r(j)) for j = k−h … k+h`, scaled by `VaR / Σ_i c_i` so the parts add up exactly (`h = 2` by default) | built |
| Component ES | `e_i = −mean of p_i(r(j)) for j = 1 … k_ES`; adds up to ES exactly | built |
| Incremental VaR | `VaR(t) − VaR(t − p_i)`: a full re-rank without the part | built for listed parts; **i6: for every part of a scope in one pass** |
| Marginal VaR | `c_i / size_i` per million of size (market value of a position, PV of a trade) | built |
| Share and standalone | `c_i / VaR`; `VaR(p_i)` | built |
| Stability of a component | the component at `h = 0`, `2` and `4`; the spread between them, shown as a band | **new** |

What i6 adds around them:

- **A computed kind** `var-decomposition` (one record per scope, grouping and date), served by the `var-engine` reactive connector,
  so a pivot, a report or another system reads components as data.
- **Any promoted column as the grouping**, including the limit letter and, where the bank maps positions to risk types (the planned
  `risk-type-map`), the risk type. A grouping by a column that is not promoted is refused with the fix, as hierarchies are.
- **Incremental VaR of all parts**: for each part one subtraction of a vector and one selection of the `k`-th smallest of `N`. For
  1.3 million positions and 250 scenarios that is 325 million subtractions and 1.3 million selections.
- **Official records** of the decomposition at the levels a site lists (default: each desk by book, the bank by desk), published in
  `var-official` as rows of a new `FIGURE` family, not a second table.

### 5.3 Liquidity-adjusted VaR

Two adjustments, both on supplied numbers, shown separately and together.

**Exit cost.** For position `i` with market value `MV_i`, relative bid-ask spread `s_i` and spread volatility `σ_i`
(`market-liquidity`):

```
LC = Σ_i ½ · |MV_i| · (s_i + κ · σ_i)                κ: drishti.var.liquidity.spread-multiplier, default 3
```

**Liquidation period.** `d_i = max(1, |Q_i| / (π_i · ADV_i))` days, or the `LIQUIDATION_DAYS` the bank sets; `π` is the
participation rate (the row's, else the setting, default 0.2). A position sold evenly over `d` days carries, relative to one day,

```
f(d) = sqrt( (1 + d)(1 + 2d) / (6d) )                f(1) = 1, f(4) = 1.3693, f(10) = 1.9621
VaR_L = VaR of the vector  t_L(s) = Σ_i f(d_i) · p_i(s)
LVaR  = VaR_L + LC
```

`f` assumes independent daily P&L of equal variance over the liquidation period; the page says so beside the figure. The scaled
vector is re-ranked, so diversification between liquid and illiquid positions is kept (a sum of scaled stand-alone VaRs would lose
it). ES is adjusted the same way. A position without liquidity data is never given a zero spread or one day
([section 15](#15-accuracy-nothing-stale-nothing-empty-nothing-silent)).

### 5.4 Concentration

| Measure | Formula |
|---|---|
| VaR share of a part | `w_i = max(c_i, 0) / Σ_j max(c_j, 0)` (hedges, with negative components, are listed apart) |
| Herfindahl index, effective number of parts | `HHI = Σ_i w_i²`; `1 / HHI` |
| Top-n share | the sum of the n largest `w_i` (n = 1, 5, 10) |
| Diversification ratio | `Σ_i VaR(p_i) / VaR` |
| Exposure share by issuer, sector, country, currency, product | `|MV|` of the group over the scope's gross `|MV|`, net and gross |
| Days to liquidate | `d_i`; the share of gross market value with `d_i` above each threshold (5, 10, 20 days) |
| Size against the market | `|Q_i| / ADV_i` |

Concentration limits are rows of the limits input with a measure name ([question Q9](#21-open-questions-for-the-owner)); the one
near and breach rule of `drishti.risk.limit-near` applies.

### 5.5 Outputs and surfaces

| | |
|---|---|
| Kinds | `var-decomposition`, `lvar-result`, `concentration-result` (computed); `var-official` gains their rows |
| Commands and API | `VAR <scope> PARTS BY <column>`, `LVAR <scope>`, `CONC <scope>`; under `/api/v1/risk/var` |
| Explain | every figure opens its scenarios, the parts' values on them and the liquidity row used |
| Playground | two tabs in `PLAY VAR`: Liquidity (the `f(d)` column and the exit cost as formulas) and Concentration |
| BI | `banking.market_risk_var_decomposition`, `…_liquidity`, `…_concentration` |
| Performance (targets, [section 14](#14-performance-targets-and-placement-in-the-cluster)) | components of all positions of the bank within 1 s; incremental of all within 5 s; `VaR_L` within 2 s; all on an open date |
| Placement | with `var`: these read VaR's open date |

## 6. P&L explain

### 6.1 What it answers

For a desk, any scope and a day: what did we make, what would yesterday's positions have made (HPL), what does the risk model say
they made (RTPL), how far apart are the two and why, and does the model still qualify the desk for internal models.

### 6.2 Inputs

| Input | From | Notes |
|---|---|---|
| HPL per position | `hypothetical-pnl` | positions of T−1, market of T |
| RTPL per position | `realised-pv` (PV or P&L form) | the risk model on the same positions and move |
| APL and HPL per desk | `desk-pnl` | the figure of record for backtesting; the position rows reconcile to it |
| Sensitivities of T−1, moves of T | `SensitivityContract` | for the factor attribution, when reachable |
| VaR of T−1, the backtest count | `VarContract` | official records only for a filed test |
| Regulatory desks | `regulatory-desk` | which desks are tested |

**Why the realised move does not ride in scenario-pv.** A scenario-PV row is valued on its business date for that date's
positions, carries a vector of the window's length, and its date is delivered once, whole. The realised-move value is one number,
valued a day later, for positions that are already history. Forcing it into scenario-pv would break the vector-length rule, the
valuation-date check (`DRS-5201`) and "one complete file per date". It is its own small kind.

### 6.3 Algorithms

For a scope `S` on day `T`:

```
HPL(S)  = Σ_{i∈S} HPL_i                          RTPL(S) = Σ_{i∈S} RTPL_i
RTPL_i  = REALISED_PV_i · FX_T − BASE_PV_i · FX_{T−1}        (PV form; the P&L form is taken as sent)
Unexplained(S) = HPL(S) − RTPL(S)
APL(S) − HPL(S) = new and amended trades + intraday + fees + reserves + other        (the supplied lines of desk-pnl and finance-pnl; the rest is shown as "not attributed", never spread over the lines)
```

**Factor attribution** (when sensitivities and moves are reachable), over the risk factors `f` of the scope:

```
E(S) = Σ_f δ_f · Δx_f  +  ½ Σ_f γ_f · Δx_f²  +  Σ_f ν_f · Δσ_f  +  θ          (each term only where its measure was delivered)
Residual(S) = HPL(S) − E(S)
```

with `δ, γ, ν, θ` summed over the scope at T−1 and the moves in the sensitivities' own units. A factor with a sensitivity and no
move is not moved by zero: its line reads *no move delivered* and its sensitivity is listed at stake. The attribution is shown by
risk class and by the largest factors, with the stale and proxied moves marked.

**The P&L attribution test** per regulatory desk, on the most recent 250 business days of `(HPL_t, RTPL_t)` pairs (thresholds from
the parameter file):

```
Spearman   ρ = Pearson correlation of rank(HPL) and rank(RTPL), ties given their mean rank
KS         D = max over x of | F_HPL(x) − F_RTPL(x) |, F the empirical distribution functions
Zone       green: ρ > 0.80 and D < 0.09;  red: ρ < 0.70 or D > 0.12;  amber: otherwise
```

A day on which either figure is missing or incomplete for the desk is left out and counted; fewer than the parameter file's
minimum of days answers *not enough days (n of 250)*, never a zone.

**Backtesting** stays one implementation, in the VaR engine, where the VaR series is
([question Q4](#21-open-questions-for-the-owner)). **Built today** (`VarBacktesting`,
[REGULATORY_VAR_REPORTING.md section 4.6](../guides/REGULATORY_VAR_REPORTING.md#46-backtesting)): the official VaR of the day
before at 99% and 97.5% against the desks' actual and hypothetical P&L and against Drishti's own risk-theoretical P&L (the official
record's `hypoPnl`: the node's P&L under the newest scenario day), for the bank, a division, a legal entity or a desk, over 250
tested days, with the zone, the multiplier from a configured table, the count read (actual, hypothetical or the higher), Kupiec
and Christoffersen. **i6 adds**, in the same class and settings:

```
The internal-models multiplier table as one more configured table:
       0–4 green 1.50 · 5: 1.70 · 6: 1.76 · 7: 1.83 · 8: 1.88 · 9: 1.92 (amber) · 10 or more: 2.00 (red)
The desk rule:  more than 12 exceptions at 99%, or more than 30 at 97.5%, in 12 months → the desk leaves the internal models
p-value of a day:  the share of the previous day's scenarios whose P&L is at or below the day's P&L
RTPL:  the exact figure from realised-pv where it is delivered; the newest-scenario figure stays, labelled approximate, where it is not
```

### 6.4 Outputs and surfaces

| | |
|---|---|
| Kinds | `pnl-attribution` (scope, date), `pla-result` (regulatory desk, date); the stored `pnl-explain` kind retires in the same release and `PNL-<book>` opens `PNL BOOK:<book>` ([contracts/README.md section 14](../contracts/README.md#14-retiring-the-stored-version)) |
| Commands and API | `PNL <scope>`, `PLA <desk>`; `/api/v1/risk/pnl` |
| Explain | each figure opens the positions behind it; an unexplained P&L opens its largest positions and the attribution's residual by factor |
| Playground | `PLAY PLA`: the two series, the ranks, the two distribution functions and both statistics as workbook formulas |
| BI | `banking.market_risk_pnl_` (attribution by desk, day, risk class), `…_pla` |
| Official | per regulatory desk and node per date: APL, HPL, RTPL, unexplained, completeness, and the test's statistics and zone; published as `pnl-official` |
| Memory | position rows of the 5 newest dates hot (2 doubles and the ids' index: about 60 MB a date at 1.3 million positions); node sums of every date from the official records |
| Performance (targets) | a date's two inputs loaded within 30 s; any desk within 100 ms; the test for 150 desks within 1 s |
| Placement | beside `var` when there is room (two small calls per desk view); otherwise anywhere |

## 7. Stress testing

### 7.1 Inputs

`stress-definition` (the scenarios), scenario-pv rows of the date's stress-test scenario set (the values the bank priced), and
through contracts: sensitivities and moves (the estimate and reverse stress), VaR (scopes beyond its own columns, and a window
day as a scenario).

### 7.2 How the scenario values ride in scenario-pv

No new vector table. The date's stress-test set is a `scenario-set` row with `SET_TYPE STRESS_TEST` and `SCENARIO_IDS`, the
scenario ids in the vectors' order (an additive field of scenario-set 1.2; `SCENARIO_DATES` stays for dated sets). Each position
sends one more scenario-pv row for that set, with one value per scenario. This is the mechanism the stressed-VaR set already uses
(a second set on the date, its rows apart). The VaR engine ignores sets of this type; the stress engine loads only them. So a
bank's pricing batch has one output format for VaR, stressed VaR and stress tests.

### 7.3 Algorithms

| Kind of test | Method | Exact or estimate |
|---|---|---|
| **Historical** (a named episode) and **hypothetical**, with values | `P&L(S, c) = Σ_{i∈S} (PV_i(c) · FX(c) − BASE_PV_i · FX_0)` for scenario `c` | exact: a sum of supplied values |
| A day of the VaR window as a scenario | `VarContract.vector`, the element of that day | exact |
| A scenario with shocks only, or one built on the screen | `Σ_f δ_f · x_f + ½ Σ_f γ_f · x_f² + Σ_f ν_f · v_f`, `x` the shock of the factor's selector | **estimate**, marked on every figure; the count and weight of sensitivities no selector shocked is shown |
| **Regulatory** | the scenarios of a `PROGRAMME`, grouped and totalled as the programme asks | as its scenarios |
| **Reverse**, from supplied scenarios | for a target loss `L`: the scenarios whose loss reaches `L`, most plausible first; and for the others the multiple `λ_c = L / loss_c` that would reach it | the multiple is a linear estimate |
| **Reverse**, the most likely move | with `δ` the scope's sensitivities and `Σ` the covariance of the factors' moves (`market-move` history): `x* = −L · Σδ / (δᵀΣδ)`, at Mahalanobis distance `L / sqrt(δᵀΣδ)` | a linear, normal estimate, marked; refused with fewer observations than the setting (default 60) |

The matrix is every scenario of a set against the children of a scope; the worst scenario and the worst child are marked; a limit
per scenario (or for any scenario) gives the use. Shocks are applied at once: no time passes and nothing is rebalanced.

### 7.4 Outputs and surfaces

| | |
|---|---|
| Kinds | `stress-pnl` (scope, scenario, date), `stress-matrix` (scope, set, date), `reverse-stress` (scope, target, date); the stored `stress-result`, `stress-scenario-pnl` and `stress-scenario` retire in the same release, `SCN` and `STR` keep their words and old ids open the computed pages |
| Commands and API | `STR <scope> [scenario]`, `SCN <id>`, `RSTR <scope> <loss>`; `/api/v1/risk/stress` |
| Explain | a cell opens its positions' P&L under the scenario; an estimate opens the shocks times sensitivities |
| Playground | `PLAY STRESS`: build a scenario from shocks, see the estimate against the nearest supplied scenario |
| BI | `banking.market_risk_stress_` (scope × scenario × date) |
| Official | per node, scenario and date; published as `stress-official` |
| Memory | 1.3 million positions × 60 scenarios, float32 hot copy: about 310 MB a date |
| Performance (targets) | any scope's matrix within 1 s on an open date; reverse stress over 2,000 factors within 2 s |
| Placement | anywhere; small |

## 8. FRTB-SA, completed

The method is built and documented ([FRTB_SA_GUIDE.md](../guides/FRTB_SA_GUIDE.md)); this section lists only what i6 adds.

| Item | What |
|---|---|
| Scopes by contract | [section 4](#4-contracts-between-engines-how-frtb-sa-and-simm-stop-holding-varservice): `needs: [sensitivities]`, `uses: [var]` |
| The computed kind | `frtb-sa-result` served by a reactive connector `frtb-sa-engine` (today the API answers; the kind is design) |
| Official records | per hierarchy node and per regulatory desk per date; published as `frtb-official` |
| The default risk and residual inputs | `default-risk` and `residual-risk` become active contracts with demo data; without them the answer keeps saying *not computed*, never 0 |
| Runs over a set of desks | `chargeOfDesks`: the charge of all desks together, of the internal-models desks together and of each desk, which the internal models capital formula needs. The charge is not additive across desks, so each set is its own run; the weighted sensitivities per desk are computed once and reused across the sets |
| The stored kind | `frtb-sensitivity` retires; `FRTB-<id>` opens the computed page |
| Regimes | Basel first; the OSFI parameter file as its own file ([question Q12](#21-open-questions-for-the-owner)) |
| Performance (targets) | the bank at 1.3 million positions and about 13 million sensitivity elements within 5 s on a loaded date; a desk within 300 ms; the three desk sets of the capital formula within 15 s |
| Placement | with `sensitivities` (it reads sums by factor through the contract; the same node avoids the hop) |

Out of scope unless the owner asks: the default risk charge for securitisations and the correlation trading portfolio.

## 9. FRTB-IMA

### 9.1 What it answers

The market-risk capital of the desks approved for internal models: which desks qualify today, their expected shortfall with
liquidity horizons, the add-on for risk factors that cannot be modelled, and the total with the standardised fallback.

### 9.2 Inputs

`es-scenario-pv` (the partial-shock values), `risk-factor-observation`, `nmrf-stress`, `regulatory-desk`, `capital-input` (a
supervisor's multiplier override), the factor dictionary's `LIQUIDITY_HORIZON` (through `SensitivityContract`); and from other
engines the attribution test, the backtest count, the standardised charge of desk sets and the default risk charge.

### 9.3 Algorithms

**Step 1, the risk factor eligibility test** per factor, over the 12 months to the date, counting at most one observation a day:

```
modellable  ⇔  (observation days ≥ 24  and  no 90-day period has fewer than 4)   or   observation days ≥ 100
```

The result is compared with the dictionary's `MODELLABLE` flag the bank sends; a difference is listed (the bank's flag is what its
engine used to build the vectors, so a difference means the vectors and the test disagree and the desk's figure is marked).

**Step 2, expected shortfall by liquidity horizon**, for a scope `S`, calibration `q ∈ {FC, RC, RS}` and risk-class set `C`:

```
t_j(s)      = Σ_{i∈S} pnl_i(q, C, j, s)                 j = 1…5 for the steps LH_j = 10, 20, 40, 60, 120
ES_j        = −mean of the ⌈N(1 − 0.975)⌉ worst of t_j  (the engine's one ES rule: the 7 worst of 250)
ES(q, C)    = sqrt( ES_1² + Σ_{j=2..5} ( ES_j · sqrt( (LH_j − LH_{j−1}) / 10 ) )² )
```

A step a position declared zero contributes nothing to `t_j`; a declared step that is missing refuses the position.

**Step 3, the stress calibration** and **step 4, the capital charge for modellable factors**:

```
ES(C)   = ES(RS, C) · max( ES(FC, C) / ES(RC, C), 1 )
IMCC    = ρ · ES(ALL) + (1 − ρ) · Σ_{c ∈ IR, CS, EQ, FX, CM} ES(c)            ρ = 0.5
check:    ES(RC, ALL) / ES(FC, ALL) ≥ 0.75   (the reduced set explains enough; below it the figure is marked and the desk listed)
```

**Step 5, the stress add-on for non-modellable factors.** For factor `k` and scope `S`:
`SES_k = max( −Σ_{i∈S} up_i,k , −Σ_{i∈S} down_i,k , 0 )`, then

```
SES = sqrt( Σ_{i ∈ idiosyncratic credit} SES_i² ) + sqrt( Σ_{j ∈ idiosyncratic equity} SES_j² )
      + sqrt( ( ρ' · Σ_{k ∈ other} SES_k )² + (1 − ρ'²) · Σ_{k ∈ other} SES_k² )          ρ' = 0.6
```

**Step 6, desk zones.** A regulatory desk is in the internal models when it is `MODEL_ELIGIBLE`, passes the desk backtest
([6.3](#63-algorithms)) and its attribution test is green (set G) or amber (set A). A red desk, a desk failing the backtest and a
desk not eligible are capitalised by the standardised approach (set U).

**Step 7, capital.**

```
C_A     = max( IMCC_{t−1} + SES_{t−1} ,  m_c · mean60(IMCC) + mean60(SES) )        m_c from the bank's backtest zone (6.3) or the supervisor's override
IMA_GA  = C_A + DRC                                                                DRC from the drc engine: max(latest, 12-week mean)
k       = 0.5 · Σ_{i∈A} SA_i / Σ_{i∈G∪A} SA_i
Surcharge = k · max( 0 , SA_GA − IMA_GA )
Capital = min( IMA_GA + Surcharge + C_U ,  SA_all ) + max( 0 , IMA_GA − SA_GA )
```

`SA_i` is desk `i`'s standardised charge alone, `SA_GA` that of the G and A desks together, `C_U` that of the U desks together,
`SA_all` that of every desk together, all from `FrtbSaContract`. The 60-day means read the engine's own official records; with
fewer than 60 the result says *history short: n of 60* and shows the figure on the days it has, marked.

### 9.4 What Drishti does not decide

The bank chooses the reduced factor set, the stress period and the stress scenarios of non-modellable factors, and prices all of
them. Drishti checks what it can from the numbers (the 75% rule, the eligibility test against the bank's flags, completeness) and
shows the rest as supplied.

### 9.5 Outputs and surfaces

| | |
|---|---|
| Kinds | `frtb-ima-result` (scope or regulatory desk, date), `rfet-result` (risk factor, date) |
| Commands and API | `FRIMA <scope>`, `RFET <factor or class>`; `/api/v1/risk/frtb-ima` |
| Explain | capital opens the formula with its terms; a desk's ES opens the five steps, the three calibrations and the scenarios in each tail; a factor opens its observation days |
| Playground | `PLAY FRIMA`: the cascade, the scaling, the class sum, the add-on and the capital formula as workbook formulas on a small desk |
| BI | `banking.market_risk_frtb_ima_` (desk × date: zone, ES by class and step, SES, capital terms), `…_rfet` |
| Official | per regulatory desk and for the bank per date, with the node vectors that reproduce it; published as `frtb-ima-official` |
| Memory and load | [section 13.2](#132-the-partial-shock-scenario-values-sized): node vectors on the heap (tens of MB), position vectors streamed at load into a mapped hot file and summed in float64 as they pass |
| Performance (targets) | a date's delivery loaded and every desk summed within 10 minutes from the lake, within 30 s from the hot file; a desk's answer within 50 ms; the capital of the bank within 1 s once the needs have answered |
| Placement | its own node when the bank delivers at position grain (disk and page cache); never beside `var` on a node sized for VaR alone |

## 10. The default risk charge by simulation

### 10.1 Inputs

`default-risk` 1.1 (per position and issuer: `JTD` or its parts, `LGD`, seniority) and `issuer-default` (per issuer: `PD`, the
sector factor, the two loadings).

### 10.2 Algorithm

```
Net jump-to-default of issuer n in scope S:     J_n = Σ_{i∈S, issuer n} JTD_i            (a long is positive, a short negative; netting within an issuer by the seniority rule of the shared library)
Asset return of issuer n on path m:             X_n = β_G,n · Z_m + β_S,n · Y_m,s(n) + sqrt(1 − β_G,n² − β_S,n²) · ε_m,n
Default on path m:                              X_n < Φ⁻¹( max(PD_n, 0.0003) )           (a defaulted issuer defaults on every path)
Loss on path m:                                 L_m = Σ_n 1{default} · J_n
DRC(S) = the ⌈0.999 · M⌉-th smallest of L_1 … L_M, floored at 0
Figure used for capital = max( the latest weekly DRC , the mean of the last 12 weekly DRCs )
```

`Z`, each sector factor `Y_s` and each `ε` are independent standard normals: two types of systematic factor, as the rules ask.
The horizon is one year with constant positions; a 60-day horizon for the equity sub-portfolio is a setting, off by default.

**Reproducible to the last bit.** Path `m` draws from a counter-based generator keyed by `(seed, m, stream)`, and normals come from
one fixed inverse-normal algorithm on `StrictMath`. The same seed gives the same loss on every path whatever the thread count, the
node or the order of work; a golden can therefore assert the exact figure. The seed is a setting, recorded in the provenance.

**How good is the estimate.** The paths are split into 20 batches; the spread of the batch quantiles gives a standard error, shown
beside the figure. Above the setting's tolerance the answer says *not converged at M paths*; an official run doubles `M` up to the
setting's maximum.

**Contributions.** Issuer `n`'s contribution is the mean of its loss over the paths whose total loss ranks within a window around
the quantile, scaled to add up to the DRC; shown by issuer, sector, rating and desk.

**What needs no simulation, shown beside it:** the expected loss `Σ_n PD_n · max(J_n, 0)` and the largest single-issuer loss.

### 10.3 Outputs and surfaces

| | |
|---|---|
| Kinds | `drc-result` (scope, date) |
| Commands and API | `DRC <scope>`; `/api/v1/risk/drc` |
| Explain | the figure opens its loss distribution, the tail's paths (which issuers defaulted, the factors' draws) and the contributions |
| Playground | `PLAY DRC`: up to 50 issuers; the workbook holds the thresholds, the conditional default probabilities and an exact enumeration for small cases |
| BI | `banking.market_risk_drc_` (scope × week; by issuer, sector, rating) |
| Official | weekly (the business day of a setting), per regulatory desk set and for the bank; published as `drc-official` |
| Performance (targets) | 5,000 issuers and 500,000 paths within 60 s on 24 cores; a desk of 200 issuers within 3 s |
| Placement | anywhere with processor cores to spare; it is weekly and bursty, so its bulkhead matters more than its node |

## 11. SIMM, completed

Built and documented ([SIMM_GUIDE.md](../guides/SIMM_GUIDE.md)); its list of what is not built is
[SIMM_GUIDE.md section 12](../guides/SIMM_GUIDE.md#12-what-is-not-built-yet). i6 does this much of it:

| Item | What |
|---|---|
| Scopes by contract | [section 4](#4-contracts-between-engines-how-frtb-sa-and-simm-stop-holding-varservice): `needs: [sensitivities]`, `uses: [exposure, var]` |
| The computed kind and official records | `simm-result` per netting set, regime and date through a reactive connector `simm-engine`; published as `simm-official`; the stored `simm` figures retire |
| Contributions | the margin by risk class, bucket and trade (Euler, adding up), and the incremental margin of a candidate trade from its supplied sensitivities |
| CRIF | export of a netting set's CRIF; the line-level comparison with the counterparty's (`crif-external` becomes a planned contract) |
| Performance (targets) | a netting set within 200 ms; 10,000 netting sets at end of day within 60 s |
| Placement | with `sensitivities` |

Left for the owner's answer ([question Q13](#21-open-questions-for-the-owner)): regime eligibility per trade, equity repo and the
SIMM-only vertices, credit look-through of indices. The parameters stay the bank's licensed file.

## 12. What every engine ships

So that no engine is half an engine, each ships all of this in its own build step
([playground for every metric](../guides/VAR_GUIDE.md), the owner's standing rule):

| Layer | Rule |
|---|---|
| Golden tests | the worked examples of its documentation page, asserted exactly ([section 16](#16-test-strategy-and-the-hand-computed-goldens)) |
| Computed kind and reactive connector | the answer is readable as data by command, link, API, CLI, search and AI tools, with the same entitlements |
| Explain | every number on its pages opens how it was computed, from which rows, by which engine version on which node |
| Playground | a grid, the explanation and a workbook of real formulas |
| BI | its tables on the pivot engine, declared in the manifest |
| Official capture | after the date's inputs are complete and its needs are official; published to `risk-results/`, never read back |
| Storage parity | every input loads identically from Delta, Iceberg and the file store, proven by a parity test |
| Screens | Drishti Elements only; a new screen is chosen from mock-ups first |
| Manifest | `contract`, `locality: [date]`, `resources`, `timeouts`, `official`; the manifest check of i5 passes |
| Documents | its page of the market-risk section, the contract pages, CONFIGURATION, CHANGELOG, in the same step |

## 13. Data the bank must supply, and its size

### 13.1 Per business date, at 1.3 million positions and a window of 250

| Input | Rows a date | Size a date (about) | Needed for |
|---|---|---|---|
| scenario-pv, the VaR set | 1.3 M, a vector of 250 | 2.6 GB of values | VaR (exists) |
| scenario-pv, the stressed-VaR set | 1.3 M × 250 | 2.6 GB | stressed VaR (exists) |
| scenario-pv, the stress-test set | 1.3 M × 60 scenarios | 0.6 GB | stress testing |
| hypothetical-pnl | 1.3 M | 40 MB | P&L explain, the attribution test |
| realised-pv | 1.3 M | 35 MB | the same |
| desk-pnl | one per desk | under 1 MB | backtesting (exists) |
| position-sensitivity | 1.3 M records, about 10 elements each | 0.3 GB | FRTB-SA, SIMM, attribution, the stress estimate (exists) |
| market-move | one per factor (50,000) | 3 MB | attribution, reverse stress |
| **es-scenario-pv** | 8 to 9 vectors a position expected | **21 to 23 GB** (dense worst case 234 GB) | FRTB-IMA |
| nmrf-stress | about 200,000 | 8 MB | FRTB-IMA |
| risk-factor-observation | about 20,000 | under 1 MB | FRTB-IMA |
| default-risk | about 200,000 | 15 MB | both default charges |
| issuer-default | 20,000, on change | 2 MB | the DRC simulation |
| market-liquidity | 200,000 | 10 MB | liquidity-adjusted VaR, concentration |
| stress-definition | about 100, on change | under 1 MB | stress testing |

Sizes are values at 8 bytes before compression, or Parquet for the row tables; they are estimates to be replaced by measured
figures in BENCHMARK.md.

### 13.2 The partial-shock scenario values, sized

One vector for every position is `1,300,000 × 250 × 8 bytes = 2.6 GB`. The internal models approach asks, per position, for up
to 3 calibrations × 6 risk-class sets × 5 liquidity-horizon steps = **90 vectors: 234 GB a date** if sent densely. The contract
removes what carries no information:

| Rule of the contract | Effect | Vectors a position |
|---|---|---|
| Dense | | 90 |
| A position in one risk class sends only the `ALL` set (its `ALL` vectors are that class's) | most positions | 15 |
| A step is sent only when the position has a factor of that horizon or longer; the others are declared zero | with an assumed mix (every position has step 10; 70% reach 20; 45% reach 40; 30% reach 60; 10% reach 120): 2.55 of 5 steps | 7.65 |
| Positions in several classes (assumed 5%, 2.5 classes each) send their classes too | adds about 0.96 | **about 8.6** |

`8.6 × 2.6 GB ≈ 22 GB` a date as 64-bit values. The assumed mix must be replaced by the bank's own; the sizing formula is in the
planner ([Drishti-Sizing-Planner.xlsx](Drishti-Sizing-Planner.xlsx) gains a sheet in the build step).

What reduces it further, each the bank's or the supervisor's choice:

| Option | Size a date | Cost |
|---|---|---|
| Only the positions of desks with internal-models approval | in proportion (often half) | none: the others are capitalised by the standardised approach |
| The current-period calibrations (FC, RC) refreshed weekly where the supervisor allows | a third on the other days | the ratio of step 3 is up to a week old, and said |
| **Desk grain** instead of position grain | 150 desks × 90 × 250 × 8 bytes = **27 MB** | no drill below a desk, no ad-hoc scope, no check of the desk's sum; the bank's engine does the aggregation Drishti was to do |

**Recommendation:** position grain with the sparse rules for the desks with approval; the contract also accepts desk grain as a
first delivery so a bank can start ([question Q5](#21-open-questions-for-the-owner)).

**In Drishti.** The load streams the rows, adds each vector into its desk's and its nodes' sums in float64, and writes the
position vectors to a mapped hot file as float32 (about 11 GB a date) for the drill. Only the node vectors are on the heap. The
lake keeps position-grain dates for the setting's number of business dates (default 65: the 60-day means and a margin for
restatements); the official record of each date keeps the node vectors that reproduce its capital, for the full retention
([question Q6](#21-open-questions-for-the-owner)).

## 14. Performance targets and placement in the cluster

The per-engine targets are in each engine's section; they are **targets**, measured in the release's benchmark step on the
benchmark machine (never on a developer machine, where tests stay at 10,000 positions) and written to BENCHMARK.md.

A placement that follows the graph, for four nodes:

```yaml
# config/placement.yaml (format 2 of i5), an example
engines:
  var:           { nodes: [node-a, node-d] }      # the heavy memory; two nodes for fail-over and date shards
  pnl-explain:   { nodes: [node-a] }              # beside var: its calls to var stay local
  stress:        { nodes: [node-a] }
  sensitivities: { nodes: [node-b] }
  frtb-sa:       { nodes: [node-b] }              # beside sensitivities
  simm:          { nodes: [node-b] }
  frtb-ima:      { nodes: [node-c] }              # its own disk and page cache for the hot file
  drc:           { nodes: [node-c] }              # weekly bursts, its own bulkhead
```

| Engine | Memory budget (heap) | Threads | Locality |
|---|---|---|---|
| `var` with the i6 additions | as today, plus one scaled total vector per liquidity question | as today | date |
| `pnl-explain` | 512 MB | 4 | date |
| `stress` | 1 GB | 4 | date |
| `frtb-sa` | 2 GB | 8 | date |
| `frtb-ima` | 2 GB heap; the hot file on disk, bounded by the disk setting | 8 for the load | date |
| `drc` | 512 MB | all cores inside its bulkhead, at low priority | date |
| `simm` | 1 GB | 8 | date |

On one node everything runs together as today; the budgets then matter (the isolation of
[CLUSTER_I5_DESIGN.md section 7.4](CLUSTER_I5_DESIGN.md#74-isolation-between-engines-on-one-node)). Remote equals local: every
golden of [section 16](#16-test-strategy-and-the-hand-computed-goldens) is run once with the engines on one node and once with
each engine on its own, and the figures must be bit-equal.

## 15. Accuracy: nothing stale, nothing empty, nothing silent

The rules every engine of i6 follows; each has a test.

| Situation | What happens |
|---|---|
| An input of the date is missing, late or failed | the engine answers the date as *not delivered* with the load's state; it does not answer an older date under the newer date's label |
| A table that keeps the last delivery (liquidity, issuer parameters, the dictionary) is old | the answer carries its age in business days; over the setting's limit it is refused |
| A position has VaR vectors and no row in a second input (HPL, liquidity, ES vectors, issuer parameters) | it is counted and listed under a named heading with the amount at stake, and the figure is marked incomplete; it is never given zero, a default spread, a default PD or a one-day period |
| A value failed to price upstream | the row is `failed`, counted, named; never zero |
| A step or risk class is absent from the partial-shock delivery | zero only if the position declared it; otherwise the position is refused |
| A dependent engine's need has no official record | no official record is built on an unofficial figure; an on-request answer says which inputs were unofficial |
| A dependent engine's need is unreachable | the refusal of i5 in words (`DRS-1035`), in milliseconds |
| An estimate (the stress estimate, reverse stress, the approximate RTPL, a curvature from gamma) | marked on the figure, in exports, in the kind's record and in BI as a column; never in an official regulatory figure |
| Two inputs disagree (desk P&L against the position rows' sum; the bank's modellable flag against the test) | both are shown with the difference; the figure of record is stated |
| A Monte Carlo figure | carries its seed, paths and standard error; not converged is said |
| Runs are mixed (batch ids, scenario sets, risk models) | the date is not loaded |

New refusals take new DRS codes, assigned in the build steps and listed in the API reference's error table.

## 16. Test strategy and the hand-computed goldens

### 16.1 Layers

| Layer | What | Where |
|---|---|---|
| Mathematics goldens | each worked example below, asserted exactly (sums, ranks, selections: equality; square roots and normal quantiles: to 1e-12 relative) | `banking-compute` unit tests; fixtures as JSON beside the test |
| Intake | every refusal and marking of [section 15](#15-accuracy-nothing-stale-nothing-empty-nothing-silent), one test each, on files of a few rows | `banking-service` |
| Contract conformance | every contract interface: records only, bit-equal doubles across the stub, the i5 suite | i5's check |
| Service | each engine on the 10,000-position test data: API, kind, Explain, official record, restatement | `banking-service` |
| Remote equals local | the goldens with each engine on its own node | the i5 proof scenario, extended |
| Storage parity | each new input from Delta, Iceberg and the file store, identical answers | the parity suite |
| Playground workbooks | the workbook's formulas recompute the engine's figure | as for VaR, FRTB-SA, SIMM |
| Screens | the page walk and layout checks; visual snapshots of the three new main pages | the browser lane |
| Scale | 1.3 million positions | the benchmark step only |

### 16.2 The goldens

Each is small enough to check by hand, is the worked example of its documentation page, and is the playground's default case.
Signs: P&L positive for a gain; VaR, ES and losses positive.

**G1. Decomposition, liquidity and concentration.** Three positions, ten scenarios, `α = 80%` so `k = 2`, component window 3.

| Scenario | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| P1 | −8 | 3 | 1 | 5 | −2 | 4 | 2 | −1 | 6 | 0 |
| P2 | 1 | −9 | −7 | 2 | 3 | −1 | 4 | 5 | −3 | 2 |
| P3 | 2 | 1 | −4 | −6 | 1 | 3 | −2 | 2 | 1 | −1 |
| Total | −5 | −5 | −10 | 1 | 2 | 6 | 4 | 6 | 4 | 1 |

| Figure | Working | Value |
|---|---|---|
| VaR, ES | 2nd worst of the total; mean of the 2 worst (−10, −5) | **5**; **7.5** |
| Standalone VaR | P1, P2, P3 | 2, 7, 4 (sum 13) |
| Component VaR | tail scenarios 3, 1, 2: raw 1.3333, 5, 0.3333 (sum 6.6667), scaled by 5 / 6.6667 = 0.75 | **1.00, 3.75, 0.25** (sum 5) |
| Component ES | scenarios 3 and 1 | 3.5, 3.0, 1.0 (sum 7.5) |
| Incremental VaR | 5 − VaR(total − P_i) = 5 − 8, 5 − 3, 5 − 6 | **−3, 2, −1** |
| Shares, HHI, effective number | 0.20, 0.75, 0.05; 0.04 + 0.5625 + 0.0025 | HHI **0.605**; **1.6529** |
| Diversification ratio | 13 / 5 | **2.6** |
| Liquidation days and `f` | 1, 4, 2.5 days | 1, 1.369306, 1.183216 |
| `VaR_L` | the scaled total's two worst: −13.318009 (scenario 3), −8.140542 (scenario 2) | **8.140542** |
| Exit cost, `κ = 3` | market values 1000, 500, 400; spreads 0.10%, 0.60%, 0.20%; spread volatilities 0.02%, 0.20%, 0.05% → 0.8 + 3.0 + 0.7 | **4.5** |
| LVaR | 8.140542 + 4.5 | **12.640542** |

**G2. The attribution test.** Ten days (the real test uses 250; the playground runs any length).

| Day | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| HPL | 12 | −5 | 3 | 8 | −11 | 0 | 6 | −2 | 9 | −7 |
| RTPL | 10 | −6 | 5 | 7 | −8 | 1 | 2 | −3 | 11 | −4 |
| Unexplained | 2 | 1 | −2 | 1 | −3 | −1 | 4 | 1 | −2 | −3 |
| Rank of HPL | 10 | 3 | 6 | 8 | 1 | 5 | 7 | 4 | 9 | 2 |
| Rank of RTPL | 9 | 2 | 7 | 8 | 1 | 5 | 6 | 4 | 10 | 3 |

Squared rank differences sum to 6, so Spearman `ρ = 1 − 6·6 / (10·99) =` **0.963636**. The two distribution functions differ by at
most one step: KS `D =` **0.1**. With the thresholds above: `ρ` is green, `D` is amber, so the desk is **amber**. The unexplained
P&L sums to −2.

**G3. Backtest zones.** With the internal-models table, counts 4, 5, 9, 10 at 99% give green 1.50, amber 1.70, amber 1.92, red 2.00; a desk with 13 exceptions at
99% or 31 at 97.5% leaves; with 12 and 30 it stays. One case each side of every boundary.

**G4. Expected shortfall by liquidity horizon.** Step figures 100, 60, 40, 20, 10:
`100² + 60²·1 + 40²·2 + 20²·2 + 10²·6 = 18,200`, `ES =` **134.907376**.

**G5. The stress calibration and the class sum.** `ES(RS) = 150`, `ES(FC) = 120`, `ES(RC) = 100`: `ES = 150 × 1.2 =` **180**
(and with `ES(FC) = 90` the ratio is floored at 1: 150). `ES(ALL) = 180`, classes 110, 60, 40, 30, 20:
`IMCC = 0.5 × 180 + 0.5 × 260 =` **220**. The 75% check: 100 / 120 = 0.8333, passes.

**G6. The stress add-on.** Idiosyncratic credit 3 and 4; no idiosyncratic equity; others 6 and 8:
`5 + 0 + sqrt( (0.6 × 14)² + 0.64 × 100 ) = 5 + sqrt(134.56) =` **16.6**.

**G7. Capital.** `IMCC_{t−1} = 205`, `SES_{t−1} = 16.6`, 60-day means 190 and 15, `m_c = 1.5`:
`C_A = max(221.6, 300) =` **300**. `DRC = 40`: `IMA_GA = 340`. `SA_GA = 420`, amber desks' `SA = 120`: `k = 0.5 × 120 / 420 =
0.142857`, surcharge `0.142857 × 80 =` **11.428571**. `C_U = 90`, `SA_all = 560`:
`min(441.428571, 560) + max(0, 340 − 420) =` **441.428571**.

**G8. The eligibility test.** Four factors built on a calendar: 24 observation days evenly spread (passes); 24 days with a 90-day
gap holding 3 (fails); 100 days all in four months (passes by the second rule); 23 days (fails).

**G9. The default simulation.**

- *Exact, independent issuers* (both loadings 0): issuer A with `J = 100`, `PD = 2%`; issuer B with `J = 60`, `PD = 5%`. The loss is
  0 with probability 0.931, 60 with 0.049, 100 with 0.019, 160 with 0.001. `P(L ≤ 100) = 0.999`, so the 99.9% figure is **100**; at
  99.95% it is 160. The simulation must give 100 for any seed once converged, and the playground's enumeration gives it exactly.
- *Conditional default probability*: `PD = 2%`, `β_G = 0.5`, `β_S = 0.3`, `Z = −2`, `Y = −1`: the issuer's own weight is
  `sqrt(1 − 0.25 − 0.09) = 0.812404` and the conditional probability `Φ( (−2.053749 + 1.0 + 0.3) / 0.812404 ) =` **0.176755**.
- *Large homogeneous pool* (the closed form the simulation must approach): `PD = 1%`, `β_G² = 0.2`, no sector loading, 10,000
  equal issuers: the 99.9% loss fraction is `Φ( (Φ⁻¹(0.01) + sqrt(0.2) · Φ⁻¹(0.999)) / sqrt(0.8) ) =` **0.145525**, asserted to a
  tolerance from the standard error.
- *A hedge*: a long and a short of the same issuer of equal `JTD` net to zero and contribute nothing on any path.
- *The seeded golden*: 50 issuers, 100,000 paths, seed 20261115: the exact figure, asserted bit-equal on 1 thread and 24.

**G10. Stress.** *Sum:* two positions with base values 100 and 50 and scenario values 80 and 65: P&L −20 and +15, the scope
**−5**. *Reverse:* sensitivities `δ = (3, 4)`, covariance `[[4, 1], [1, 1]]`, target loss 10: `Σδ = (16, 7)`, `δᵀΣδ = 76`,
`x* =` **(−2.105263, −0.921053)** (check: `3 × −2.105263 + 4 × −0.921053 = −10`), distance `10 / sqrt(76) =` **1.147079**.

**G11. Attribution of one position.** DV01 −19,565.62 per basis point (the sample record of position-sensitivity) and a move of
+3 bp: the delta line is **−58,696.86**; with HPL −58,211.40 the residual is **485.46**.

**G12 and G13.** FRTB-SA and SIMM keep their hand-built cases ([FRTB_SA_GUIDE.md section 10](../guides/FRTB_SA_GUIDE.md#10-the-hand-built-cases),
[SIMM_GUIDE.md section 10](../guides/SIMM_GUIDE.md#10-the-hand-built-cases)); i6 adds a desk-set case (the charge of two desks
together is below the sum of each) and a contributions case (the margin's parts add up).

The numbers above were computed outside the product for this design and are to be recomputed by hand, and by a second person,
when each fixture is written.

## 17. Documentation

The owner asked for the structure BI has: one master page and a page per engine or measure, each with theory, mathematics, worked
examples that double as tests, data contracts with JSON, loading, and reading results. The skeleton is written:
[market-risk-i6/README.md](market-risk-i6/README.md) (the master page), [the VaR page](market-risk-i6/VAR.md), which documents the engine as it is built today (inputs with JSON lines, how a date loads, the algorithm, hierarchies, currencies, limits, Explain, backtesting, official results, intraday, shards, measured performance) and marks what i6 adds, and seven pages for the decomposition and the new measures. FRTB-SA and SIMM keep their guides until the move.

**Why the skeleton is under `architecture/` for now.** A page under `docs/guides/` must be in the feature register and in Help
(`config/docs-features.yaml`, the documentation gates), and customer documents describe only what is built. The pages move to
`docs/guides/market-risk/` in the build step that ships each engine, with their register and Help entries.

**The move of the existing guides**, without breaking a link:

| Today | Becomes | How links survive |
|---|---|---|
| `guides/VAR_GUIDE.md` (7,100 lines) | `guides/market-risk/VAR.md`, and its Parts II to IV as `VAR_DATA_CONTRACT.md` and `VAR_ENGINE.md` if the page budget asks | the Help slug `docs-guides-var-guide` keeps resolving through an alias in `help.yaml` (as `docs-architecture-sensitivities-and-frtb-sa` does today); every section number is kept so `VAR_GUIDE.md section 18.9` in contracts and code comments is rewritten by one scripted pass; the link checker of the gates proves no link is left |
| `guides/REGULATORY_VAR_REPORTING.md` | `guides/market-risk/REGULATORY_REPORTING.md` | the same |
| `guides/FRTB_SA_GUIDE.md`, `guides/SIMM_GUIDE.md` | `guides/market-risk/FRTB_SA.md`, `SIMM.md` | the same |
| `contracts/PNL.md`, `STR.md`, `FRTB.md` (parked designs) | their content that is still true folds into the engine's page; the files are deleted when their engine ships | `replaced-by` of the retiring kinds points at the new page |

The move is one step of the build plan, done after the engines' pages exist and outside a drill, in one commit, with the scripted
pass and the gates run before the push. A folder counts as one guide in the register, so the count caps are not raised.

## 18. Screens

New user-facing screens are chosen from mock-ups before they are built. Three main screens have a first mock-up
([mockups/market-risk-i6/](mockups/market-risk-i6/README.md)); each is one option, and alternatives follow the owner's first
reaction rather than being drawn before it.

| Screen | Mock-up | Built from |
|---|---|---|
| P&L explain, the desk view | [pnl-explain-desk.html](mockups/market-risk-i6/pnl-explain-desk.html) | the grid, the chart library's waterfall and scatter, zone chips, the Explain drawer |
| FRTB-IMA, desk zones and capital | [frtb-ima-capital.html](mockups/market-risk-i6/frtb-ima-capital.html) | the grid, the stacked bar, the formula card, the drawer |
| Stress results | [stress-results.html](mockups/market-risk-i6/stress-results.html) | the grid as a heat-map matrix, the bar chart, the scenario drawer |
| Liquidity and Concentration | tabs of the VaR page | existing VaR page parts |
| DRC | a results page of the standard engine layout (figure cards, the distribution, contributions) | mock-up when its step starts |
| The eligibility test | a grid with a calendar strip per factor | mock-up when its step starts |
| Reverse stress | a panel of the stress page | in the stress mock-up |

All are Drishti Elements; none has bespoke screen code. A part an Elements library lacks (the waterfall, the calendar strip) is
added to the library.

## 19. Build plan

Sizes: S about a day, M two to three days, L about a week, for one builder. Every step ships its tests, documents and CHANGELOG
lines, deletes what it replaces, and passes its pre-merge gate. At most three builders at once.

| Step | What | Size | After | Deletes |
|---|---|---|---|---|
| **M0** | Decisions: the questions of section 21; the mock-ups chosen; the Basel paragraphs and thresholds confirmed into draft parameter files | S | — | — |
| **M1** | Contracts in `banking-api`; the scope library and `ScopeIndex`; FRTB-SA and SIMM onto `EngineRef` and the library; manifests (`needs` → `uses`); the conformance check clean | M | i5 step C5 | `VarService` imports in `frtb/`, `simm/`; `FrtbService.Scopes` |
| **M2** | `stats/` as the one home of rank, quantile, ES, Spearman, KS, covariance; VaR calls it; goldens unchanged | S | — | VaR's private copies |
| **M3** | Decomposition: the kind, groupings, incremental of all parts, the stability band, official rows, BI | M | M2 | — |
| **M4** | `market-liquidity` intake; liquidity-adjusted VaR; concentration; the playground tabs; G1 | M | M3 | — |
| **M5** | `pnl-explain`: intake of HPL and RPV, sums, unexplained, the reconciliation with desk P&L, the factor attribution with `market-move`, API, kind, connector, official record; G11 | L | M1 | the stored `pnl-explain` kind |
| **M6** | The attribution test; the backtest's internal-models table, desk rule and p-values, the exact RTPL in it; the desk view; `PLAY PLA`; G2, G3 | M | M5 | — |
| **M7** | `stress`: definitions, the stress-test set (scenario-set 1.2), sums, matrix, limits, the estimate, scenarios built on the screen, the page; G10 (sum) | L | M1 | `stress-result`, `stress-scenario-pnl`, `stress-scenario` |
| **M8** | Reverse stress; regulatory programmes; `PLAY STRESS`; G10 (reverse) | M | M7 | — |
| **M9** | FRTB-SA completed: the kind and connector, official records, default-risk and residual-risk active with demo data, desk-set runs | M | M1 | `frtb-sensitivity` |
| **M10** | `frtb-ima` part 1: the partial-shock intake (streamed, declared zeros), the cascade, the calibration, the class sum; G4, G5 | L | M2 | — |
| **M11** | The eligibility test and the stress add-on; G6, G8 | M | M10 | — |
| **M12** | Desk zones, capital, the 60-day means from official records, the page, `PLAY FRIMA`; G7 | M | M6, M9, M11, M13 | — |
| **M13** | `drc`: intake, the shared netting library, the simulation, convergence, contributions, the weekly record, `PLAY DRC`; G9 | L | M1 | — |
| **M14** | SIMM completed: the kind, official records, contributions, the CRIF export and comparison | M | M1 | the stored `simm` figures |
| **M15** | Demo data for every new input from generators (small, synthetic, JSONL); the contracts turn active; storage parity | M | each engine's intake | — |
| **M16** | MR cube `uses`; BI tables reviewed together; Explain for every number walked | S | M3–M14 | — |
| **M17** | Documentation: the market-risk section, the move of the existing guides, tutorials, the deck chapter | L | M3–M14 | `contracts/PNL.md`, `STR.md`; the old guide paths |
| **M18** | Cluster proof (each engine on its own node, bit-equal goldens, end of day in order) and the benchmark at 1.3 million positions | M | all | — |

About 6 L and 12 M: roughly twelve builder-weeks, five calendar weeks with three builders, plus drills.

**Order with three builders.** After M0 to M2: track 1 = M3 → M4 → M7 → M8; track 2 = M5 → M6 → M13; track 3 = M9 → M14 → M10 →
M11. Then M12, M15 as intakes land, M16, M17, M18.

**Two releases, recommended** ([question Q1](#21-open-questions-for-the-owner)): **i6a** = M0 to M9 and M14 with their parts of
M15 to M18 (the VaR extensions, P&L explain, stress, FRTB-SA and SIMM completed), one feature drill and one hardening drill;
**i6b** = M10 to M13 (FRTB-IMA and the DRC), the same. The owner's rule of at most two large items a release, and the fact that
i6b's inputs are the ones a bank is least likely to have ready, both point the same way.

## 20. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | A bank cannot supply partial-shock values, realised-move values or HPL per position | every contract states a smaller first delivery (desk grain; the approximate RTPL, marked; desk P&L only); the engines answer what the data supports and name the rest; ask a bank early (Q5) |
| R2 | The volume of the partial-shock values (22 GB a date expected, 234 GB dense) | the sparse rules; streaming load with node sums; position vectors on disk only; limited lake retention; the benchmark step before release |
| R3 | A regulatory formula or threshold is transcribed wrongly | everything in parameter files per regime; M0 confirms each against the text with its paragraph; the goldens are computed by hand by two people; a bank's or auditor's review before the word *regulatory* appears on a page |
| R4 | The stated estimates are mistaken for exact figures | marked on the figure, the export, the kind and BI; excluded from official regulatory records; a guard test on the mark |
| R5 | The simulation is not reproducible across threads or nodes | counter-based streams keyed by path; `StrictMath`; the seeded golden on 1 and 24 threads and across nodes |
| R6 | HPL and RTPL are not aligned (different position dates, calendars, late trades) | `POSITION_DATE` checked on both; completeness by desk; a desk under the threshold is left out of the test for the day, in words |
| R7 | Moving backtesting or the scope code breaks the working VaR pages | backtesting is extended in place, not moved (Q4); the scope library is the same code relocated, with VaR's goldens and scope tests unchanged as the proof |
| R8 | The i5 lookup is not shipped when i6 starts | M2 to M4 need nothing of i5 and start first; M1 waits for C5 |
| R9 | Retiring four stored kinds at once breaks old links | each retirement follows the five steps of the contracts README, with the id mapping and the guard, in its engine's own step |
| R10 | The guide move breaks links | one scripted pass, aliases for Help slugs, the link gate; done outside a drill |
| R11 | ISDA SIMM parameters and CRIF definitions are licensed | unchanged: shipped empty, the bank's licensed file by version |
| R12 | The release grows past what two drills can prove | the two-release split (Q1) |
| R13 | The release label: IMPLEMENTATION_PLAN lists 2.9.11.i6 as *banking on the public kernel API only* | the owner confirms the numbering (Q2); M1 already removes banking's engine-to-engine coupling, which that item also wants |

## 21. Open questions for the owner

Each has a recommendation; a "yes" to the recommendation needs no more words.

| # | Question | Recommendation |
|---|---|---|
| **Q1** | One release, or two (i6a: VaR extensions, P&L explain, stress, FRTB-SA and SIMM completed; i6b: FRTB-IMA and the DRC)? | **Two.** Six large items in one release breaks the two-large-items rule, and i6b depends on bank data that may not exist yet |
| **Q2** | IMPLEMENTATION_PLAN still names 2.9.11.i6 *banking on the public kernel API only*. Is market risk the new i6 and that item renumbered? | Market risk is i6 as agreed on 2026-11-15; the kernel-API item becomes i7. A row pointing here is added to the plan; the old row is left for the owner to renumber |
| **Q3** | Scopes: the scope grammar as a library each engine runs on its own rows, with VaR asked only for what the rows lack (so FRTB-SA and SIMM no longer need VaR)? Or keep all scope resolution in VaR behind `VarContract.members`? | **The library.** One implementation, no engine-to-engine hop for the common scopes, and FRTB-SA runs with VaR off. The alternative is smaller to build but keeps the `needs` |
| **Q4** | Backtesting: extend it where it is (the VaR engine) and show it in the P&L explain desk view, or move it into the P&L explain engine? | **Extend in place.** It works, the VaR series is there, and a bank running VaR without P&L explain keeps its backtest. One implementation either way |
| **Q5** | Partial-shock values: position grain (about 22 GB a date, any scope, full drill) or desk grain (27 MB, desk figures only)? | **Position grain as the contract, desk grain accepted as a first delivery**, marked on the result. Please ask one bank what its engine can deliver |
| **Q6** | Retention of position-grain partial-shock dates in the lake: 65 business dates, with the official record keeping the node vectors for the full period? | **Yes.** 7 years at position grain would be about 5.5 TB a year |
| **Q7** | The realised-move values as their own kind (`realised-pv`), with today's RTPL (the official record's `hypoPnl`, the newest scenario day on the date's own positions) kept only where it is absent, labelled approximate, and never used for the attribution test? | **Yes** |
| **Q8** | Stress values in scenario-pv under a `STRESS_TEST` scenario set (one format from the pricing batch), instead of the separate `scenario-pnl` table the parked STR.md proposed? | **Yes**: one table for scenario values, as the owner asked for VaR |
| **Q9** | Limits for stress, concentration and sensitivities: extend `var-limit` with a measure name, or one new `risk-limit` input for every measure (the parked STR.md proposal), with `var-limit` folded into it? | **One `risk-limit`**, `var-limit` folded in the same release: one way for limits |
| **Q10** | The DRC's loadings: delivered by the bank (the contract as written), or estimated by Drishti from supplied equity or spread series? | **Delivered first.** Estimation is statistics, not pricing, and can follow as an option |
| **Q11** | The liquidity model: the exit cost plus the liquidation-period scaling of section 5.3, with the participation rate and the spread multiplier as settings? | **Yes**, both shown separately and together |
| **Q12** | Regimes for the parameter files: Basel first, then OSFI CAR chapter 9; the US rule when a client needs it? | **Yes** |
| **Q13** | SIMM's remaining items (regime eligibility per trade, equity repo and SIMM-only vertices, index look-through): in i6 or later? | **Later**, unless a client needs one |
| **Q14** | Securitisation and correlation-trading default charges in FRTB-SA? | **Out of i6** |
| **Q15** | Mock-ups: one option per screen is drawn. Draw alternatives now, or after a first reaction? | **After a first reaction**, to spend the effort where the owner wants a choice |
| **Q16** | The guide move (VAR_GUIDE and the others into `guides/market-risk/`): in i6a's documentation step? | **Yes**, in one commit outside a drill |
| **Q17** | Who checks the regulatory formulas against the text (risk R3)? | A named reviewer at a bank or an auditor before release; until then pages say *computed by the Basel formula as implemented*, not *regulatory capital* |

---

*Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. Unauthorised copying, use or distribution is prohibited.*
