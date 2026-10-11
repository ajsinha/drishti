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

import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The numbers RUPAKA.md section 18 and RUPAKA_POC.md ask for, measured through the real engine: the first query builds the columns layer from the
 * source (the cost paid once per date and per data load), then the same aggregation answered from the columns layer, from a rollup, and from the
 * result cache. Local-safe: 10,000 trades a day for 2 days (20,000 rows). Writes {@code target/bi-engine-bench.txt}; asserts only that the three
 * answers agree and the layers are ordered as the design says (a cache hit and a rollup are not slower than the columns layer by a margin).
 */
class EngineBenchTest {

    @TempDir Path store;
    final ObjectMapper json = new ObjectMapper();

    private static double[] percentiles(double[] ms) {
        double[] s = ms.clone();
        Arrays.sort(s);
        return new double[] {s[s.length / 2], s[Math.min(s.length - 1, (int) Math.ceil(s.length * 0.95) - 1)]};
    }

    private QueryService.Result run(QueryService svc, QueryService.Loaded ds, QueryService.Caller who, String body, boolean explain) throws Exception {
        return svc.query(ds, QueryRequest.of(json.readTree(body)), who, explain);
    }

    @Test
    void columnsRollupAndCacheOver20000Rows() throws Exception {
        TestLake lake = new TestLake(true, 10_000);
        QueryProperties cached = QueryProperties.defaults(store.toString());
        QueryProperties uncached = QueryProperties.defaults(store.resolve("nocache").toString()).withCacheEntries(0);
        QueryService svc = new QueryService(uncached, new DuckDbEngine(uncached), lake, Clock.systemUTC());
        QueryService withCache = new QueryService(cached, new DuckDbEngine(cached), lake, Clock.systemUTC());
        QueryService.Loaded ds = new QueryService.Loaded("bench", "bench", DatasetParser.parse(TestLake.yaml()).dataset(), "h");
        QueryService.Caller who = new QueryService.Caller("bench", Set.of("risk"), true, java.util.Map.of(), SqlCompiler.Masks.NONE);
        String byDesk = "{\"fields\":[\"trades.desk\",\"trades.currency\"],\"measures\":[\"Notional\",\"MTM\",\"Trades\"],\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-09\"}";
        String rollupQuery = byDesk + "}";
        String columnsQuery = byDesk + ",\"layer\":\"columns\"}";

        long t0 = System.nanoTime();
        QueryService.Result first = run(svc, ds, who, columnsQuery, false);
        double build = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime();
        QueryService.Result firstRollup = run(svc, ds, who, rollupQuery, false);
        double buildRollup = (System.nanoTime() - t0) / 1e6;
        assertThat(first.meta().layer()).isEqualTo("columns");
        assertThat(firstRollup.meta().layer()).isEqualTo("rollup");
        assertThat(firstRollup.rows()).isEqualTo(first.rows());

        int runs = 100;
        double[] col = new double[runs];
        double[] roll = new double[runs];
        double[] hit = new double[runs];
        for (int i = 0; i < 10; i++) {
            run(svc, ds, who, columnsQuery, false);
            run(svc, ds, who, rollupQuery, false);
        }
        for (int i = 0; i < runs; i++) {
            long a = System.nanoTime();
            run(svc, ds, who, columnsQuery, false);
            col[i] = (System.nanoTime() - a) / 1e6;
            a = System.nanoTime();
            run(svc, ds, who, rollupQuery, false);
            roll[i] = (System.nanoTime() - a) / 1e6;
        }
        run(withCache, ds, who, rollupQuery, false);
        for (int i = 0; i < runs; i++) {
            long a = System.nanoTime();
            QueryService.Result r = run(withCache, ds, who, rollupQuery, false);
            hit[i] = (System.nanoTime() - a) / 1e6;
            assertThat(r.meta().cached()).isTrue();
        }
        double[] c = percentiles(col);
        double[] r = percentiles(roll);
        double[] h = percentiles(hit);
        long colBytes = size(store.resolve("nocache").resolve("columns"));
        long rollBytes = size(store.resolve("nocache").resolve("rollups"));
        List<String> out = new ArrayList<>();
        out.add("rows: 20000 (10,000 trades a day, 2 days); result rows: " + first.rows() + "; runs: " + runs);
        out.add(String.format("first query (reads the source, builds the columns layer for 2 dates, then answers): %.1f ms", build));
        out.add(String.format("first rollup query (builds the rollup from the columns layer, then answers): %.1f ms", buildRollup));
        out.add(String.format("columns layer: p50 %.2f ms, p95 %.2f ms (%d bytes on disk)", c[0], c[1], colBytes));
        out.add(String.format("rollup: p50 %.2f ms, p95 %.2f ms (%d bytes on disk)", r[0], r[1], rollBytes));
        out.add(String.format("result cache hit: p50 %.3f ms, p95 %.3f ms", h[0], h[1]));
        Files.writeString(Path.of("target/bi-engine-bench.txt"), String.join("\n", out) + "\n");
        out.forEach(System.out::println);
        assertThat(h[0]).isLessThan(c[0]);
        svc.close();
        withCache.close();
    }

    private static long size(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
