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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.TestLake;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The BI scale gate (docs/admin/BENCHMARK.md, section 19): one model query over the test lake at the most a local test may hold (10,000
 * trades a day for two days, 20,000 rows; two facts and a dimension), timed and its Java allocation counted, against budgets taken from
 * measured numbers with headroom. It fails when the BI engine grows slower or hungrier past them, a regression the pivot benchmark
 * (section 16) would only show at scale. A JUnit test: it runs in the drill's Java lanes, never in the gate's static checks. Writes
 * {@code target/bi-scale-gate.txt}.
 */
class BiScaleGateTest {

    /** The budgets (BENCHMARK.md 19.2): the figure measured on the reference workstation, times the headroom given there. */
    static final double COLD_MS = 4_000;                    // the first query: reads the source, builds the columns layer of two dates, answers
    static final double WARM_P95_MS = 250;                  // the same query from the columns layer (no result cache), 95th percentile of RUNS
    static final long WARM_ALLOCATED_BYTES = 64L << 20;     // Java bytes allocated on the asking thread by one warm query (median of RUNS)
    static final int RUNS = 30;

    static final String QUERY = "{ mq: 1, model: desk-risk, select: { fields: [desks.region, desks.desk, trades.currency], "
            + "measures: [MTM, Notional, Trades, AvgMTM, Limit, Usage] } }";

    @TempDir Path store;

    @Test
    void aModelQueryOverTwentyThousandRowsStaysInsideItsTimeAndMemoryBudgets() throws Exception {
        TestLake lake = new TestLake(true, 10_000);
        QueryProperties props = QueryProperties.defaults(store.toString()).withCacheEntries(0);   // every warm query reads the columns layer
        QueryService service = new QueryService(props, new DuckDbEngine(props), lake, Clock.systemUTC());
        try {
            MqService mq = new MqService(MqProperties.defaults());
            MqHarness h = new MqHarness(service, TestLake.DAY2);
            ModelView model = MqHarness.model(MqEngineTest.MODEL, MqEngineTest.TRADES, MqEngineTest.DESKS, MqEngineTest.LIMITS);
            com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long me = Thread.currentThread().threadId();

            long t0 = System.nanoTime();
            Map<String, Object> first = mq.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse(QUERY), false).body();
            double cold = (System.nanoTime() - t0) / 1e6;
            List<?> rows = (List<?>) first.get("rows");
            assertThat(rows).isNotEmpty();
            double counted = rows.stream().mapToDouble(r -> ((Number) ((List<?>) r).get(5)).doubleValue()).sum();
            assertThat(counted).isEqualTo(lake.trades.get(TestLake.DAY2).size());   // the newest date's 10,000 trades, each counted once

            for (int i = 0; i < 5; i++) {
                mq.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse(QUERY), false);
            }
            double[] warm = new double[RUNS];
            long[] bytes = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                long before = threads.getThreadAllocatedBytes(me);
                long a = System.nanoTime();
                Map<String, Object> again = mq.ask(h.bind(model, MqHarness.RITA, "m"), MqParser.parse(QUERY), false).body();
                warm[i] = (System.nanoTime() - a) / 1e6;
                bytes[i] = threads.getThreadAllocatedBytes(me) - before;
                assertThat(again.get("rows")).isEqualTo(first.get("rows"));
            }
            Arrays.sort(warm);
            Arrays.sort(bytes);
            double p50 = warm[RUNS / 2];
            double p95 = warm[(int) Math.ceil(RUNS * 0.95) - 1];
            long allocated = bytes[RUNS / 2];
            List<String> out = new ArrayList<>();
            out.add("rows: 20000 trades (10,000 a day, 2 days), desks and limits; answer rows: " + rows.size() + "; warm runs: " + RUNS);
            out.add(String.format("cold (source read, columns layer built, answered): %.1f ms (budget %.0f)", cold, COLD_MS));
            out.add(String.format("warm (columns layer, no result cache): p50 %.2f ms, p95 %.2f ms (budget %.0f)", p50, p95, WARM_P95_MS));
            out.add(String.format("warm Java allocation on the asking thread: median %d bytes (budget %d)", allocated, WARM_ALLOCATED_BYTES));
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/bi-scale-gate.txt"), String.join("\n", out) + "\n");
            out.forEach(System.out::println);
            assertThat(cold).as("cold query, ms").isLessThan(COLD_MS);
            assertThat(p95).as("warm query p95, ms").isLessThan(WARM_P95_MS);
            assertThat(allocated).as("warm query, bytes allocated").isLessThan(WARM_ALLOCATED_BYTES);
        } finally {
            service.close();
        }
    }
}
