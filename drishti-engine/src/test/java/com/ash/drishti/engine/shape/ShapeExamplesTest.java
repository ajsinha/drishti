/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.engine.shape;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The JSON of each documented example infers the roles its panels need, and validates against its own shape. */
class ShapeExamplesTest {

    private static final Path DIR = Path.of("..", "docs", "guides", "examples");

    private static Shape shape(String name) throws IOException {
        JsonNode doc = ShapeTestSupport.JSON.readTree(Files.readString(DIR.resolve(name + ".json")));
        Shape s = ShapeTestSupport.service().infer(List.of(new Sample(name + ".json", doc)));
        assertThat(new MiniSchemaValidator(s.schema()).validate(doc)).as(name).isEmpty();
        return s;
    }

    private static void roles(String example, Map<String, String> expected) throws IOException {
        Shape s = shape(example);
        expected.forEach((path, role) -> assertThat(ShapeTestSupport.role(s, path)).as(example + " " + path + " in " + s.roles()).isEqualTo(role));
    }

    @Test
    void everyExampleValidatesAgainstItsOwnShape() throws IOException {
        try (Stream<Path> files = Files.list(DIR)) {
            List<String> names = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.replace(".json", "")).toList();
            assertThat(names).hasSizeGreaterThanOrEqualTo(10);
            for (String n : names) {
                shape(n);
            }
        }
    }

    @Test
    void allPanelsShowcaseHasLinksIdsMeasuresAndStatus() throws IOException {
        roles("all-panels-showcase", Map.of("$.TRADE_ID", "id", "$.COUNTERPARTY", "link", "$.NOTIONAL", "measure", "$.MATURITY_DATE", "date",
                "$.CONFIRMATION.STATUS", "status", "$.NETTING_SET", "link"));
    }

    @Test
    void exposureProfileIsASeriesOnATenorAxis() throws IOException {
        roles("exposure-profile", Map.of("$.PROFILE", "series", "$.LIMIT", "measure"));
    }

    @Test
    void linkedSourcesLinksItsCounterparty() throws IOException {
        roles("linked-sources", Map.of("$.COUNTERPARTY", "link"));
    }

    @Test
    void marketChartsHasCandlesAGridAndAHistory() throws IOException {
        roles("market-charts", Map.of("$.OHLC", "ohlc", "$.GRID", "grid", "$.HISTORY", "series"));
    }

    @Test
    void operationsStatusHasStatusesAScheduleAndATimeline() throws IOException {
        roles("operations-status", Map.of("$.CONFIRMATION.STATUS", "status", "$.CLEARING.STATUS", "status", "$.SCHEDULE", "series",
                "$.LIFECYCLE.TIMELINE", "events"));
    }

    @Test
    void pivotRowGroupsHasDimensionsAndMeasures() throws IOException {
        roles("pivot-row-groups", Map.of("$.POSITIONS", "table", "$.POSITIONS[].DESK", "dimension", "$.POSITIONS[].BOOK", "dimension",
                "$.POSITIONS[].MTM", "measure"));
    }

    @Test
    void pnlExplainHasStepsASensitivityLadderAndMeasures() throws IOException {
        roles("pnl-explain", Map.of("$.PNL_EXPLAIN", "steps", "$.SENSITIVITIES", "series", "$.MTM", "measure", "$.RISK.DV01", "measure"));
    }

    @Test
    void relationshipsHasAGraphAndLinks() throws IOException {
        roles("relationships", Map.of("$.HIERARCHY", "graph", "$.COUNTERPARTY", "link", "$.NETTING_SETS", "table"));
    }

    @Test
    void riskDistributionHasADistributionOfScenarioPnl() throws IOException {
        roles("risk-distribution", Map.of("$.SCENARIO_PNL", "distribution", "$.VAR99", "measure", "$.BOOKS", "table"));
    }

    @Test
    void treeTableHasATree() throws IOException {
        roles("tree-table", Map.of("$.UNITS", "tree", "$.UNITS[].EXPOSURE", "measure"));
    }
}
