<!-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. Drishti. Proprietary: see LICENSE. -->
# R2 cut plan: Drishti on Drishti Risk

Status: plan for review (Fable) before any in-tree risk code is deleted. Branch `i8/develop`. Design: DRISHTI_RISK_DESIGN.md (sections 0a,
5, 7, 9, 10) and ENGINE_INTEGRATION_DESIGN.md (sections 0, 9, 12). Gate: A8, "Risk from one dropped JAR, through configuration, with the
goldens bit-identical".

## 1. The fixed figures (never edited)

| Proof | Where | Figures |
|---|---|---|
| Stack headline | packaged server over the committed sample lake, 2026-09-30, 509-day window, USD | BANK VaR 167,372,692, ES 210,131,972; DESK:RATES VaR 74,633,680, ES 79,246,576 |
| `PackagedVarGoldenTest` | `testing/drishti-modules-it` | 12 cases (3 dates x 2 windows x 2 currencies), > 1,500 scopes, members exact, VaR/ES within the test's existing rule, limit and use |
| `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden` | `modules/banking/java/*/src/test` | expected files unedited |
| Recorder (RK-18) | drishti-risk `tck/recorded/**` (harvested from Drishti's goldens, raw bits) | every double as its raw bits |

No expected value, tolerance or golden file is changed by any step of this plan. A step that needs one changed stops and is reported.

## 2. What moves, what Drishti keeps

Moves (already copied, with history, into `drishti-platform/drishti-risk` at R1; Drishti's copies are deleted only at step 6):

| Drishti today | Risk module |
|---|---|
| `compute.vector`, `compute.linalg` (drishti-compute), `server.playground`, `OpenBlasEngine` | `risk-kernel` |
| `banking-compute/var`, `/frtb`, `/analytics`, `MethodChoices`; `banking-service/var` engine half (VarBook, VarService maths, loaders, tiers, captures model, explain, playgrounds) | `risk-market` + `risk-engine-market` (`market-risk`) |
| `banking-compute/ccr`, `/simm`; `banking-service/exposure`, `/simm` engine half | `risk-ccr` + `risk-engine-ccr` (`ccr`) |

Drishti keeps (banking module, naming no `com.ash.risk` type after step 6): controllers (old routes become aliases of
`/api/v1/engines/{id}/...` or ask through the host by output name), access (`VarAccess`, `ExposureAccess` produce a `Visibility`), pivot
adapters (ask by output name until M3), sign-off and approvals, saved views and basket edits, official sinks, Flight form, intraday
stream, `BankingVocabulary`, `RiskOperations`; the three packs; `tools/mrloader`, `tools/drishti.py var|exp|frtb|simm`.

## 3. Engine-host wiring

1. drishti-risk builds `drishti-risk-<version>.jar`: one shaded JAR holding `risk-kernel`, `risk-market`, `risk-ccr`,
   `risk-engine-market`, `risk-engine-ccr` and the private libraries; platform packages (engine API, tools, Lake, Rachana) excluded; both
   engines registered for `ServiceLoader`. Today the repository makes one JAR per engine: step 1 adds the merged-JAR module.
2. Drishti has no Maven dependency on it. Packaging copies the JAR (version and SHA-384 listed in the distribution) into
   `<home>/lib/engines`. A test that needs it copies it into a `@TempDir` lib folder (the drop-in pattern of `EngineHostTest`).
3. `config/engines/market-risk.yaml` and `ccr.yaml` bind inputs to the packs' kinds (`scenario-pv` -> `market-risk/scenario-pv`,
   `fx-scenario`, `scenario-set`, `var-limit`, `system-basket`, `var-official` history, ...; `ccr`: `exposure-path`, `simulation-run`,
   `netting-set`, `csa`, `agreement`, `collateral-balance`, `credit-limit`), and carry `options`: hierarchies and position rules from
   today's `var.yaml` and the market-risk pack (old keys read as aliases), `linear-algebra` from `openblas.yaml`.
4. The banking module asks through `EngineHost` (`HostedEngine.ask` by output name: `node-var`, `basket-var`, `headline`, ...), with the
   caller's `Visibility` from `VarAccess`.

## 4. Steps, each a small commit green on its targeted tests

| Step | What | Proven by |
|---|---|---|
| 0 | i8/develop builds and `./mvnw clean verify` passes (step 1 of the lane) | full verify |
| 1 | drishti-risk builds; its unit tests, kit (`MarketKitIT`, `CcrKitIT`) and recorder cases pass; merged JAR module | `./mvnw clean verify` in drishti-risk; recorder raw bits equal |
| 2 | Engine files `market-risk.yaml`, `ccr.yaml` (bindings, options); the server starts with the JAR in `lib/engines` and both engines `running` | `EngineHostTest`-style drop-in test with the Risk JAR; Admin -> Engines |
| 3 | **Side by side**: a test-only comparison (`RiskSideBySideIT` in `drishti-modules-it`) starts the packaged server with the Risk JAR dropped in and, for every golden scope, asks both the in-tree route (`/api/v1/risk/var/scope`) and the engine (`/api/v1/engines/market-risk/output` `node-var`/`basket-var`) | VaR and ES raw bits equal per scope; members, limit, use equal; the four stack figures |
| 4 | Golden tests call through the host: `PackagedVarGoldenTest` asks the engine route; `VarGoldenMatrixTest`, `SystemBasketGoldenTest`, `HierarchyGolden` run against the JAR in a temporary lib folder | expected files unedited, all pass |
| 5 | Banking routes switch to the engine: `/api/v1/risk/var/*` and `/api/v1/ccr/exposure/*` answer by asking the host (old routes as aliases); the in-tree path still compiled, unused | step 3's test now compares the route against the recorder; the console's VaR browser tests |
| 6 | **Deletion** (after Fable's review of this plan): `banking-compute` engine packages, the engine half of `banking-service`, `compute.vector`, `compute.linalg`, `server.playground`, `OpenBlasEngine`, `VarResultEngine`, `ExposureResultEngine`, the old engine files (aliases kept), `module.yaml compute.engines` | full verify; guard "no source imports `com.ash.risk`, no class implements `com.ash.engine.Engine`, no pom names an engine artifact" |
| 7 | Upgrade proof: Risk 1.0.0 replaced by a 1.0.1 build in `lib/engines`, server jar hash unchanged, goldens pass | the drop-in lane |

Order is strict: nothing is deleted before step 3 is green; step 6 is a separate commit series after the review.

## 5. Risks

- Inputs arrive through the engine API's `Inputs` (columns) instead of `DataNode` documents: the intake is where a number can move (knot 1).
  Step 3 compares raw bits scope by scope, so a drift is found before any route changes.
- Summation order: the in-tree server sums in its own order (the golden test allows one unit). The engine's `ParallelSums` is fixed-order;
  the side-by-side test reports both the exact-bits count and the within-one count separately, and the four stack figures must be exact.
- Features without an engine output yet (captures workflow, saved views, intraday overlay stream) stay on the host side and keep working
  through stored outputs; any route not covered by an output stays in-tree until it is, and is listed in the step-6 commit.
