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

import static com.ash.drishti.engine.shape.ShapeTestSupport.infer;
import static com.ash.drishti.engine.shape.ShapeTestSupport.role;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** One test per role of the design's table; each also checks that a reason is given. */
class ShapeRolesTest {

    private static void has(Shape s, String path, String role) {
        assertThat(role(s, path)).as(path + " in " + s.roles()).isEqualTo(role);
        assertThat(s.roles().get(path).reason()).isNotBlank();
        assertThat(s.schema().toString()).contains("\"role\":\"" + role + "\"");
    }

    @Test
    void idIsNamedLikeAnIdAndUnique() {
        Shape s = infer("{\"tradeId\":\"A1\",\"book\":\"X\"}", "{\"tradeId\":\"A2\",\"book\":\"X\"}");
        has(s, "$.tradeId", "id");
    }

    @Test
    void idByValuePatternWhenNothingInTheNameSaysSo() {
        Shape s = infer("{\"ref1\":\"MX-200001\"}", "{\"ref1\":\"MX-200002\"}");
        has(s, "$.ref1", "id");
    }

    @Test
    void aRepeatingIdLikeFieldIsNotAnId() {
        Shape s = infer("{\"tradeId\":\"A1\"}", "{\"tradeId\":\"A1\"}");
        assertThat(role(s, "$.tradeId")).isNotEqualTo("id");
    }

    @Test
    void linkIsAnIdNameObject() {
        Shape s = infer("{\"counterparty\":{\"id\":\"CP-NORTH\",\"name\":\"Northbridge\"}}");
        has(s, "$.counterparty", "link");
        assertThat(s.roles().get("$.counterparty").kind()).isEqualTo("counterparty");
    }

    @Test
    void linkIsAFieldTheCatalogueNamesOrValuesThatAreKnownIds() {
        Shape s = infer("{\"nettingSet\":\"anything\",\"other\":\"CP-ONE\"}");
        has(s, "$.nettingSet", "link");
        has(s, "$.other", "link");
        assertThat(s.roles().get("$.other").kind()).isEqualTo("counterparty");
    }

    @Test
    void measureIsANumberWithAMoneyNameOrSpread() {
        Shape s = infer("{\"notional\":1000000,\"qty\":3,\"odd\":1}", "{\"notional\":2000000,\"qty\":4,\"odd\":2}",
                "{\"notional\":3000000,\"qty\":5,\"odd\":3}", "{\"notional\":4000000,\"qty\":6,\"odd\":4}", "{\"notional\":5000000,\"qty\":7,\"odd\":5}");
        has(s, "$.qty", "measure");
        has(s, "$.odd", "measure");
        assertThat(s.roles().get("$.odd").reason()).contains("vary");
    }

    @Test
    void dimensionIsLowCardinalityText() {
        Shape s = infer("{\"book\":\"A\"}", "{\"book\":\"B\"}", "{\"book\":\"A\"}", "{\"book\":\"B\"}");
        has(s, "$.book", "dimension");
    }

    @Test
    void statusIsADimensionWhoseValuesAreStates() {
        Shape s = infer("{\"phase\":\"Live\"}", "{\"phase\":\"Settled\"}", "{\"phase\":\"Live\"}", "{\"phase\":\"Failed\"}", "{\"x\":{\"status\":\"Confirmed\"}}");
        has(s, "$.phase", "status");
        has(s, "$.x.status", "status");
    }

    @Test
    void dateIsADateOrDateTimeText() {
        Shape s = infer("{\"asof\":\"2026-09-01\",\"at\":\"2026-09-01T10:00:00Z\"}");
        has(s, "$.asof", "date");
        has(s, "$.at", "date");
    }

    @Test
    void seriesIsAListOfDateOrTenorWithNumbers() {
        Shape s = infer("{\"h\":[{\"date\":\"2026-09-01\",\"close\":1.5},{\"date\":\"2026-09-02\",\"close\":1.6}],"
                + "\"p\":[{\"tenor\":\"1M\",\"ee\":1},{\"tenor\":\"3M\",\"ee\":2}]}");
        has(s, "$.h", "series");
        has(s, "$.p", "series");
    }

    @Test
    void ohlcIsAListWithOpenHighLowClose() {
        Shape s = infer("{\"c\":[{\"date\":\"2026-09-01\",\"open\":1,\"high\":2,\"low\":0.5,\"close\":1.5}]}");
        has(s, "$.c", "ohlc");
    }

    @Test
    void distributionIsAListOfNumbersOrRecordsWithOneMeasure() {
        Shape s = infer("{\"pnl\":[1,2,3,4,5,6],\"v\":[{\"x\":1},{\"x\":2}]}");
        has(s, "$.pnl", "distribution");
        has(s, "$.v", "distribution");
    }

    @Test
    void gridIsRowsWithSeveralNumberColumnsOnOneAxis() {
        Shape s = infer("{\"g\":[{\"month\":\"X6\",\"a\":1.5,\"b\":2.5,\"c\":3.5},{\"month\":\"F7\",\"a\":1.6,\"b\":2.6,\"c\":3.6}]}");
        has(s, "$.g", "grid");
    }

    @Test
    void stepsAreLabelledSignedAmountsThatSumToAnotherMeasure() {
        Shape s = infer("{\"total\":100,\"w\":[{\"step\":\"open\",\"v\":150},{\"step\":\"a\",\"v\":-30},{\"step\":\"b\",\"v\":-20}]}");
        has(s, "$.w", "steps");
        assertThat(s.roles().get("$.w").reason()).contains("$.total");
    }

    @Test
    void stepsWithoutASumNeedBothSigns() {
        Shape s = infer("{\"w\":[{\"step\":\"a\",\"v\":5},{\"step\":\"b\",\"v\":7},{\"step\":\"c\",\"v\":9}]}");
        assertThat(role(s, "$.w")).isNotEqualTo("steps");
        Shape t = infer("{\"w\":[{\"step\":\"a\",\"v\":5},{\"step\":\"b\",\"v\":-7},{\"step\":\"c\",\"v\":9}]}");
        has(t, "$.w", "steps");
    }

    @Test
    void graphHasNodesAndEdges() {
        Shape s = infer("{\"h\":{\"nodes\":[{\"id\":\"a\"}],\"edges\":[{\"from\":\"a\",\"to\":\"a\"}]}}");
        has(s, "$.h", "graph");
    }

    @Test
    void treeIsRecursiveChildren() {
        Shape s = infer("{\"u\":[{\"name\":\"a\",\"x\":1,\"children\":[{\"name\":\"b\",\"x\":2}]}]}");
        has(s, "$.u", "tree");
    }

    @Test
    void eventsAreDatedLabelledRecords() {
        Shape s = infer("{\"t\":[{\"date\":\"2026-08-31\",\"event\":\"Booked\",\"description\":\"by the desk\"}]}");
        has(s, "$.t", "events");
    }

    @Test
    void textIsLongText() {
        Shape s = infer("{\"note\":\"" + "word ".repeat(30) + "\"}");
        has(s, "$.note", "text");
    }

    @Test
    void rolesAreIndependentOfDocumentOrder() {
        Shape a = infer("{\"k\":\"Live\",\"n\":1}", "{\"k\":\"Failed\",\"n\":2}", "{\"k\":\"Live\",\"n\":3}");
        Shape b = infer("{\"k\":\"Live\",\"n\":3}", "{\"k\":\"Live\",\"n\":1}", "{\"k\":\"Failed\",\"n\":2}");
        assertThat(a.roles()).isEqualTo(b.roles());
    }
}
