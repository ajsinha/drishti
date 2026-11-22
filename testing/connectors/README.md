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
# Connector files of the tests

A connector is defined by its file and nothing else; the server refuses a `drishti.sources.connectors.<name>.*` property or variable. Each folder here is one set: the connector files a test (or the tests listed) copies into its run folder with `ConnectorFixtures.copy("<set>")` and names as `drishti.sources.connectors-dir=${drishti.test.dir}/connectors/<set>`. A file with only `enabled: false` switches a template's connector off; a value only the running test knows is a placeholder `${test-connectors.<connector>.<setting>}` that the test's `@DynamicPropertySource` gives.

| Set | Used by |
|---|---|
| `bi-ai` | BiAiTest |
| `bi-report-api` | BiReportApiTest |
| `business-date` | BusinessDateTest, HistoryApiTest |
| `cache-admin` | CacheAdminTest |
| `calc-api` | CalcApiTest, ColumnarSearchTest |
| `dataset-api` | DatasetApiTest |
| `dist-api` | DistApiTest |
| `exposure-api` | ExposureApiTest |
| `exposure-official-hierarchies` | ExposureOfficialHierarchiesTest |
| `exposure-pre-deal-api` | ExposurePreDealApiTest |
| `field-params-api` | FieldParamsApiTest |
| `finance-lake-off` | ReportApiTest, RegistryApiTest, DerivedKindTest, SimmApiTest, FrtbApiTest (the finance pack's lake connector off) |
| `hierarchy-column-missing` | HierarchyColumnMissingTest |
| `index-reload-admin` | IndexReloadAdminTest |
| `internal-imf-fixture` | a test of its own name |
| `languages-api` | LanguagesApiTest |
| `model-acceptance` | ModelAcceptanceTest |
| `mr-cube-off` | MrCubeOffTest, VarRootNameTest, VarBusyProtectionTest |
| `news-and-markets-sutra-next-equivalence` | a test of its own name |
| `news-in-memory-sentinel` | NewsInMemorySentinelTest |
| `pack-data-api` | PackDataApiTest |
| `panel-provenance` | PanelProvenanceTest |
| `panel-source-budget` | a test of its own name |
| `pick-list-scale` | PickListScaleTest |
| `recent-store-api` | RecentStoreApiTest |
| `statement-api` | StatementApiTest |
| `statement-rows` | StatementRowsTest |
| `trading-sutra-next-equivalence` | TradingSutraNextEquivalenceTest |
| `transform-inputs-api` | TransformInputsApiTest |
| `var-by-attribute-api` | VarByAttributeApiTest |
| `var-currency-ahead` | VarCurrencyAheadTest, VarCurrencyApiTest |
| `var-deleted-date` | VarDeletedDateTest, VarMrCubeApiTest, VarBasketSheetApiTest, VarApiTest, VarPivotApiTest, VarParametricApiTest |
| `var-explain-api` | VarExplainApiTest |
| `var-hierarchy-api` | VarHierarchyApiTest |
| `var-inputs-api` | VarInputsApiTest |
| `var-intraday-api` | VarIntradayApiTest |
| `var-matrix-api` | VarMatrixApiTest |
| `var-mr-cube-cap` | VarMrCubeCapTest, VarStressApiTest, VarWindowApiTest |
| `var-playground-api` | VarPlaygroundApiTest |
| `var-pv-stream` | VarPvStreamTest |
| `var-refusal-remembered` | VarRefusalRememberedTest |
| `var-regulatory-api` | VarRegulatoryApiTest |
| `var-resources-soak` | VarResourcesSoakTest |
| `var-stress-window-api` | VarStressWindowApiTest |
| `var-system-basket-api` | VarSystemBasketApiTest |
