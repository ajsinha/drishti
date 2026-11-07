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

import com.ash.drishti.server.bi.Layered;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Records the BI parity fixtures (PIVOT_ENGINE.md section 12.4) from the code it runs on: every case of {@link BiParityCases}, each level by
 * one grouped query, written to {@code src/test/resources/bi/parity/lake-test.json}. Runs only when asked:
 *
 * <pre>
 * mvn -o -q -pl drishti-server -am -Dtest=BiParityRecorder -Ddrishti.bi.parity.record=true -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 *
 * on the tree before BI's totals moved to the pivot engine (copy this file and {@link BiParityCases} there), then commit the fixture.
 */
class BiParityRecorder {

    @TempDir Path store;

    @Test
    @EnabledIfSystemProperty(named = "drishti.bi.parity.record", matches = "true")
    void recordTheFiguresOfTheCodeItRunsOn() throws Exception {
        TestLake lake = new TestLake(true);
        QueryProperties props = QueryProperties.defaults(store.toString());
        try (QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC())) {
            QueryService.Loaded ds = new QueryService.Loaded("file:dataset:lake-test", Layers.key("file:dataset:lake-test"),
                    Layered.dataset(TestLake.yaml()).dataset(), "h1");
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("recordedWith", "one grouped query per level (BI's totals before the pivot engine)");
            Map<String, Object> cases = new LinkedHashMap<>();
            for (BiParityCases.Case c : BiParityCases.CASES) {
                cases.put(c.name(), BiParityCases.record(service, ds, c));
            }
            all.put("cases", cases);
            BiParityCases.write(all);
        }
    }
}
