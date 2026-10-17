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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A table over a kind that a union of connectors serves (one per source system) reads every one of them into one table: with two
 * systems holding the same day's rows, the table has both systems' rows, and a table naming one connector reads that one only.
 */
class UnionTableTest {

    @TempDir Path store;

    @Test
    void aTableOverAUnionKindReadsEverySourceSystem() throws Exception {
        TestLake two = new TestLake(true) {
            @Override
            public List<SourcePlugin> plugins(String connector, String kind) {
                return connector.isBlank() && kind.equals("trade") ? List.of(tradingLake, tradingLake) : super.plugins(connector, kind);
            }
        };
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), two, Clock.systemUTC())) {
            for (String source : List.of("{ kind: trade }", "{ connector: trading-lake, kind: trade }")) {
                String yaml = TestLake.yaml().replace("source: { connector: trading-lake, kind: trade }", "source: " + source);
                DatasetParser.Result r = DatasetParser.parse(yaml);
                var ds = new QueryService.Loaded("file:dataset:union-" + source.length(), Layers.key("file:dataset:union-" + source.length()), r.dataset(), "h1");
                var who = new QueryService.Caller("u", Set.of("risk"), true, UserAttributes.of("u", List.of("risk"), null, null), (t, c) -> false);
                var answer = service.query(ds, QueryRequest.of(new ObjectMapper().readTree("{\"measures\": [\"Trades\"]}")), who, true);
                int day = two.trades.get(TestLake.DAY2).size();
                assertThat(((Number) answer.columns().get(0).values()[0]).intValue()).as(source).isEqualTo(source.contains("connector") ? day : 2 * day);
            }
        }
    }
}
