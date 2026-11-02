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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.harness.FunctionHarness;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Split-and-merge equals one pass (PIVOT_ENGINE.md sections 8.3 and 20): every built-in through the harness on 10,000 seeded rows; a
 * deliberately wrong merge fails it; and on the tabular provider, the same view in one pass and split into 3 or 7 shards merged in order gives
 * the same figures (within 1e-12 relative), and the same digits on every run of the same split.
 */
class FunctionsTest {

    static Batch rows(int n, long seed) {
        Random r = new Random(seed);
        double[] x = new double[n];
        double[] w = new double[n];
        String[] t = new String[n];
        for (int i = 0; i < n; i++) {
            x[i] = i % 113 == 0 ? Double.NaN : r.nextGaussian() * 1e4 + (i % 7 == 0 ? 1e9 : 0);
            w[i] = r.nextDouble() * 100;
            t[i] = "CP-" + r.nextInt(500);
        }
        return new Batch(n, new Object[] {x, w, t});
    }

    @Test
    void everyBuiltInPassesTheHarnessOn10kRows() {
        Batch b = rows(10_000, 7);
        for (Functions.Info info : Functions.BUILT_INS) {
            String n = info.name();
            List<String> fields = switch (n) {
                case "count" -> List.of("x");
                case "wavg" -> List.of("x", "w");
                default -> List.of("x");
            };
            AggFunction<Object> f = Functions.builtIn(n, fields, "percentile".equals(n) ? List.of(0.99) : List.of()).orElseThrow();
            Batch in = n.equals("count_distinct") ? new Batch(b.rows(), new Object[] {b.columns()[2]})
                    : n.equals("wavg") ? new Batch(b.rows(), new Object[] {b.columns()[0], b.columns()[1]}) : new Batch(b.rows(), new Object[] {b.columns()[0]});
            FunctionHarness.Report rep = FunctionHarness.run(f, in, 5, 1e-12);
            assertThat(rep.passed()).as(n + ": worst " + rep.worst()).isTrue();
            assertThat(rep.runs()).hasSizeGreaterThan(30);
        }
    }

    @Test
    void aWrongMergeFailsTheHarness() {
        AggFunction<Object> sum = Functions.builtIn("sum", List.of("x"), List.of()).orElseThrow();
        AggFunction<Object> wrong = new AggFunction<>() {
            public String name() { return "bad_sum"; }
            public com.ash.drishti.bicompute.spi.Kind kind() { return sum.kind(); }
            public int inputs() { return 1; }
            public Object start() { return sum.start(); }
            public Object add(Object s, Batch b) { return sum.add(s, b); }
            public Object merge(Object a, Object b) { return a; }
            public Double finish(Object s) { return sum.finish(s); }
            public byte[] encode(Object s) { return sum.encode(s); }
            public Object decode(byte[] b) { return sum.decode(b); }
            public long bytes(Object s) { return 40; }
            public List<String> sqlState(List<String> in) { return List.of(); }
            public Object fromSql(Object[] p) { return null; }
        };
        assertThat(FunctionHarness.run(wrong, rows(1_000, 3), 2, 1e-12).passed()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shardsMergedInOrderEqualOnePassOnTheTabularProvider() {
        Map<String, Object> view = Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "columns", List.of("CCY"), "expandTo",
                Map.of("rows", 2), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "S"), Map.of("measure", "avg(PNL)", "as", "A"),
                        Map.of("measure", "var_samp(PNL)", "as", "V"), Map.of("measure", "wavg(PNL, NOTIONAL)", "as", "W"),
                        Map.of("measure", "min(PNL)", "as", "Mn"), Map.of("measure", "count(PNL)", "as", "C")));
        Map<String, Object> one;
        try (PivotFixture a = new PivotFixture(5_000, StateCache.none(), PivotCore.Settings.defaults())) {
            one = a.core.query(Viewer.of("rita"), PivotView.parse(view));
        }
        for (int shards : new int[] {3, 7}) {
            PivotCore.Settings s = new PivotCore.Settings(20_000, 5_000, 2_000, 1, 50, shards, 60, 1_000_000_000L);
            Map<String, Object> split1;
            Map<String, Object> split2;
            try (PivotFixture b = new PivotFixture(5_000, StateCache.none(), s)) {
                split1 = b.core.query(Viewer.of("rita"), PivotView.parse(view));
                split2 = b.core.query(Viewer.of("rita"), PivotView.parse(view));
            }
            Map<String, Map<String, Object>> c1 = (Map<String, Map<String, Object>>) one.get("cells");
            Map<String, Map<String, Object>> c2 = (Map<String, Map<String, Object>>) split1.get("cells");
            Map<String, Map<String, Object>> c3 = (Map<String, Map<String, Object>>) split2.get("cells");
            assertThat(c2.keySet()).isEqualTo(c1.keySet());
            for (String k : c1.keySet()) {
                Map<String, Object> v1 = (Map<String, Object>) c1.get(k).get("values");
                Map<String, Object> v2 = (Map<String, Object>) c2.get(k).get("values");
                Map<String, Object> v3 = (Map<String, Object>) c3.get(k).get("values");
                assertThat(v3).as("the same digits on every run of the same split").isEqualTo(v2);
                for (String m : v1.keySet()) {
                    if (v1.get(m) == null) {
                        assertThat(v2.get(m)).isNull();
                        continue;
                    }
                    double x = ((Number) v1.get(m)).doubleValue();
                    double y = ((Number) v2.get(m)).doubleValue();
                    assertThat(y).as(shards + " shards, " + k + " " + m).isCloseTo(x, offset(Math.max(1e-9, Math.abs(x) * 1e-12)));
                }
                assertThat(c2.get(k).get("members")).isEqualTo(c1.get(k).get("members"));
            }
        }
    }
}
