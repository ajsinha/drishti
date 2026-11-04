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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.pivot.BiAggregate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BI's totals on the pivot engine equal the figures the code before the switch gave (PIVOT_ENGINE.md section 12.4, test {@code BiParityTest}
 * of section 20): every case of {@link BiParityCases}, every level, every group, every measure, against the recorded fixture; a difference
 * beyond 1e-9 relative (exact for counts) fails naming the case, level, group, measure and both values. The engine's totals are made in one
 * grouping-sets statement per fact; the fixture's by one grouped query per level.
 */
class BiParityTest {

    @TempDir Path store;

    @Test
    @SuppressWarnings("unchecked")
    void everyTotalEqualsTheRecordedFigure() throws Exception {
        assertThat(Files.exists(BiParityCases.FIXTURES)).as("record the fixture first (BiParityRecorder, on the tree before the switch)").isTrue();
        Map<String, Object> cases = (Map<String, Object>) BiParityCases.read().get("cases");
        TestLake lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        Dataset dataset = DatasetParser.parse(TestLake.yaml()).dataset();
        List<String> problems = new ArrayList<>();
        int compared = 0;
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            QueryService.Loaded ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"), dataset, "h1");
            for (BiParityCases.Case c : BiParityCases.CASES) {
                List<Map<String, Object>> recorded = (List<Map<String, Object>>) cases.get(c.name());
                assertThat(recorded).as("case " + c.name() + " in the fixture").isNotNull();
                BiAggregate.Totals t = BiAggregate.totals(dataset, BiParityCases.LEVELS, BiParityCases.MEASURES, BiParityCases.base(c), 5000,
                        q -> service.query(ds, q, BiParityCases.caller(c.reader()), false));
                assertThat(t.notTotalled()).as(c.name()).isEmpty();
                for (Map<String, Object> level : recorded) {
                    List<String> by = (List<String>) level.get("by");
                    BiAggregate.Level mine = t.levels().stream().filter(l -> l.by().equals(by)).findFirst().orElse(null);
                    if (mine == null) {
                        problems.add(c.name() + " " + by + ": no level from the engine");
                        continue;
                    }
                    List<List<Object>> rows = (List<List<Object>>) level.get("rows");
                    if (rows.size() != mine.rows().size()) {
                        problems.add(c.name() + " " + by + ": " + mine.rows().size() + " groups, recorded " + rows.size());
                        continue;
                    }
                    for (List<Object> want : rows) {
                        List<Object> key = want.subList(0, by.size());
                        List<Object> got = mine.rows().stream().filter(r -> String.valueOf(r.subList(0, by.size())).equals(String.valueOf(key)))
                                .findFirst().orElse(null);
                        if (got == null) {
                            problems.add(c.name() + " " + by + " " + key + ": no such group from the engine");
                            continue;
                        }
                        for (int m = 0; m < BiParityCases.MEASURES.size(); m++) {
                            compared++;
                            Object a = want.get(by.size() + m);
                            Object b = got.get(by.size() + m);
                            if (!same(a, b)) {
                                problems.add(c.name() + " " + by + " " + key + " " + BiParityCases.MEASURES.get(m) + ": engine " + b + ", recorded " + a);
                            }
                        }
                    }
                }
            }
        }
        assertThat(problems).as(compared + " figures compared").isEmpty();
        assertThat(compared).isGreaterThan(500);
    }

    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        double x = ((Number) a).doubleValue();
        double y = ((Number) b).doubleValue();
        if (x == y) {
            return true;
        }
        return Math.abs(x - y) <= 1e-9 * Math.max(Math.abs(x), Math.abs(y));
    }
}
