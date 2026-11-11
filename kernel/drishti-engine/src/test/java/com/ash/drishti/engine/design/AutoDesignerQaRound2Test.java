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
package com.ash.drishti.engine.design;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.engine.shape.Sample;
import com.ash.drishti.engine.shape.Shape;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.Semantics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QA round 2 (2026-10-03) findings L-2 to L-6 and L-14: what auto-design drafts for months, tenors, single roots, codes, fractions. */
class AutoDesignerQaRound2Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Design design(String... docs) throws Exception {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < docs.length; i++) {
            samples.add(new Sample("s" + i + ".json", JSON.readTree(docs[i])));
        }
        BuilderProperties props = BuilderProperties.defaults();
        Semantics semantics = Semantics.defaults();
        Shape shape = new ShapeService(props, semantics, new ReferenceCatalog(new GraphProperties(List.of(), Map.of(), null, null, null))).infer(samples);
        return new AutoDesigner(props, semantics).design(shape, samples, "thing", null);
    }

    @Test
    void monthlySeriesAreDatedLines() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int m = 1; m <= 12; m++) {
            rows.append(m > 1 ? "," : "").append("{\"period\":\"2024-").append(String.format("%02d", m)).append("\",\"cpi\":").append(100 + m).append('}');
        }
        Design d = design("{\"id\":\"X-1\",\"series\":[" + rows + "]}");
        assertThat(d.yaml()).contains("kind: line").contains("x: \"period\"");
    }

    @Test
    void aTenorAxisWithSeveralNumberColumnsIsASurface() throws Exception {
        Design d = design("{\"id\":\"X-1\",\"vol\":[{\"tenor\":\"1M\",\"a\":1,\"b\":2,\"c\":3,\"d\":4},{\"tenor\":\"3M\",\"a\":2,\"b\":3,\"c\":4,\"d\":5},"
                + "{\"tenor\":\"1Y\",\"a\":2,\"b\":3,\"c\":4,\"d\":6}]}");
        assertThat(d.yaml()).contains("kind: surface");
    }

    @Test
    void aSingleRootTreeGetsATreeTable() throws Exception {
        Design d = design("{\"root\":{\"name\":\"A\",\"size\":3,\"children\":[{\"name\":\"B\",\"size\":1,\"children\":[]},"
                + "{\"name\":\"C\",\"size\":2,\"children\":[{\"name\":\"D\",\"size\":1,\"children\":[]}]}]}}");
        assertThat(d.yaml()).contains("rows: \"$.root.children\"").contains("children: \"@.children\"");
        Design top = design("{\"name\":\"A\",\"size\":3,\"children\":[{\"name\":\"B\",\"size\":1,\"children\":[]},{\"name\":\"C\",\"size\":2,\"children\":[]}]}");
        assertThat(top.yaml()).contains("rows: \"$.children\"").contains("children: \"@.children\"");
    }

    @Test
    void yearsVersionsAndNumbersAreNotAmounts() throws Exception {
        Design d = design("{\"id\":\"X-1\",\"year\":2023,\"customerNo\":123456,\"seq\":4,\"mtm\":12345.5,\"pnl\":-100.5}",
                "{\"id\":\"X-2\",\"year\":2024,\"customerNo\":223456,\"seq\":5,\"mtm\":1345.5,\"pnl\":100.5}");
        assertThat(d.yaml()).doesNotContain("bind: \"$.year\", fmt: \"amount0\"").doesNotContain("bind: \"$.customerNo\", fmt")
                .doesNotContain("bind: \"$.seq\", fmt");
        assertThat(d.yaml()).contains("bind: \"$.mtm\", fmt: \"amount0\"");
    }

    @Test
    void theLinksPanelIsDraftedOnlyWhenTheDataRefersToAnotherEntity() throws Exception {
        assertThat(design("{\"id\":\"X-1\",\"a\":1,\"b\":\"x\",\"rows\":[{\"d\":\"2026-01-01\",\"v\":1},{\"d\":\"2026-01-02\",\"v\":2}]}").yaml())
                .doesNotContain("kind: links");
    }

    @Test
    void theDraftTextDoesNotDependOnMapIterationOrder() throws Exception {
        Design d = design("{\"id\":\"X-1\",\"ladder\":[{\"date\":\"2026-01-01\",\"a\":1,\"b\":2},{\"date\":\"2026-01-02\",\"a\":2,\"b\":3},"
                + "{\"date\":\"2026-01-03\",\"a\":2,\"b\":3},{\"date\":\"2026-01-04\",\"a\":2,\"b\":3},{\"date\":\"2026-01-05\",\"a\":2,\"b\":3},"
                + "{\"date\":\"2026-01-06\",\"a\":2,\"b\":3}],\"c\":\"Live\",\"d\":\"Failed\"}");
        assertThat(d.yaml()).contains("{ label: \"A\", value: \"a\", tone: \"link\" }");
        assertThat(d.yaml()).doesNotContain("{ tone");
    }

    @Test
    void aShareOfAWholeIsAGaugeAndAShortListOfFourFieldRecordsIsTabs() throws Exception {
        Design d = design("{\"id\":\"X-1\",\"utilisation\":0.42,\"sets\":[{\"id\":\"A\",\"agreement\":\"CSA\",\"trades\":3,\"net\":10},"
                + "{\"id\":\"B\",\"agreement\":\"ISDA\",\"trades\":4,\"net\":20}]}");
        assertThat(d.yaml()).contains("kind: gauge").contains("max: \"1\"").contains("kind: tabs");
    }

    @Test
    void longTextIsMarkdown() throws Exception {
        Design d = design("{\"id\":\"X-1\",\"a\":1,\"b\":2,\"note\":\"" + "long text ".repeat(20) + "\"}");
        assertThat(d.yaml()).contains("kind: markdown");
    }
}
