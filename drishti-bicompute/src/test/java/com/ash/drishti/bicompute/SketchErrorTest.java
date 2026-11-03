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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.functions.Hll;
import com.ash.drishti.bicompute.functions.Kll;
import com.ash.drishti.bicompute.functions.SpaceSaving;
import com.ash.drishti.bicompute.scale.ScalePolicy;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The sketches within their stated errors on known distributions (PIVOT_ENGINE.md section 20, {@code SketchErrorTest}), on seeded data of
 * the local test size: HyperLogLog within three standard errors (1.04/√m) at every size and within one on average; KLL's rank error under
 * 1.65% at k = 200 on uniform, normal and log-normal values, merged in any split; Space-Saving finds the heavy values, never under-counts and
 * over-counts by at most its floor. Merges: HyperLogLog's and Space-Saving's are commutative and associative to the byte; KLL's repeat to
 * the byte in a fixed order. The SQL form of HyperLogLog's rank equals the Java form on DuckDB's own hash. Exact on request equals exact.
 */
class SketchErrorTest {

    static List<String> distinct(int n, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add("CP-" + Long.toHexString(r.nextLong()) + "-" + i);
        }
        return out;
    }

    static Hll hll(List<String> values) {
        Hll h = new Hll(12, Hll.FAMILY_JAVA);
        values.forEach(v -> h.addHash(Hll.hash(v)));
        return h;
    }

    @Test
    void hyperLogLogIsWithinThreeStandardErrorsAtEverySizeAndOneOnAverage() {
        double se = Hll.relativeError(12);
        assertThat(se).isBetween(0.016, 0.0163);
        for (int n : new int[] {100, 1_000, 10_000, 50_000}) {
            double sum = 0;
            for (long seed = 1; seed <= 5; seed++) {
                List<String> vals = distinct(n, seed * 31 + n);
                List<String> withRepeats = new ArrayList<>(vals);
                withRepeats.addAll(vals.subList(0, n / 2));          // repeats never count twice
                double e = hll(withRepeats).estimate();
                double rel = Math.abs(e - n) / n;
                assertThat(rel).as(n + " values, seed " + seed).isLessThan(3 * se);
                sum += rel;
            }
            assertThat(sum / 5).as("mean error at " + n).isLessThan(1.5 * se);
        }
    }

    @Test
    void hyperLogLogMergeIsCommutativeAndAssociativeToTheByteAndEqualsOnePass() {
        List<String> all = distinct(30_000, 7);
        Hll a = hll(all.subList(0, 9_000));
        Hll b = hll(all.subList(9_000, 21_000));
        Hll c = hll(all.subList(21_000, 30_000));
        byte[] one = hll(all).encode();
        assertThat(a.merge(b).merge(c).encode()).isEqualTo(one);
        assertThat(a.merge(b.merge(c)).encode()).isEqualTo(one);
        assertThat(c.merge(a).merge(b).encode()).isEqualTo(one);
        assertThat(b.merge(c).merge(a).encode()).isEqualTo(one);
        assertThat(Hll.decode(one).encode()).isEqualTo(one);
        Hll sql = new Hll(12, Hll.FAMILY_SQL);
        sql.addCode(5L * 64 + 3);
        assertThatThrownBy(() -> a.merge(sql)).isInstanceOf(IllegalStateException.class).hasMessageContaining("count a value twice");
        assertThat(new Hll(12, Hll.FAMILY_NONE).merge(sql).encode()).isEqualTo(sql.encode());
    }

    @Test
    void theSqlRankOfDuckDbsHashEqualsTheJavaRank() {
        try (PivotFixture fx = new PivotFixture(500, StateCache.none(), PivotCore.Settings.defaults())) {
            for (int p : new int[] {11, 12, 14, 16}) {
                String sql = "SELECT CAST(hash(CPTY) AS VARCHAR), " + Hll.sqlCode("CPTY", p) + " FROM trades";
                List<long[]> rows = fx.exec.query(sql, List.of(), rs -> {
                    List<long[]> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new long[] {Long.parseUnsignedLong(rs.getString(1)), rs.getLong(2)});
                    }
                    return out;
                });
                assertThat(rows).hasSize(500);
                for (long[] r : rows) {
                    long h = r[0];
                    long idx = h >>> (64 - p);
                    long w = h & ((1L << (64 - p)) - 1);
                    long rho = Long.numberOfLeadingZeros(w) - p + 1;
                    assertThat(r[1]).as("p " + p + ", hash " + Long.toUnsignedString(h)).isEqualTo(idx * 64 + rho);
                }
            }
        }
    }

    static double[] values(String dist, int n, long seed) {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = switch (dist) {
                case "uniform" -> r.nextDouble() * 1e6;
                case "normal" -> r.nextGaussian() * 5e4;
                default -> Math.exp(r.nextGaussian() * 2);       // log-normal: heavy right tail
            };
        }
        return x;
    }

    static double maxRankError(Kll s, double[] x) {
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        double worst = 0;
        for (int i = 1; i < 100; i++) {
            double q = i / 100.0;
            double v = s.quantile(q);
            int below = lowerBound(sorted, v);
            int atOrBelow = upperBound(sorted, v);
            double lo = (double) below / sorted.length;
            double hi = (double) atOrBelow / sorted.length;
            double err = q < lo ? lo - q : q > hi ? q - hi : 0;
            worst = Math.max(worst, err);
        }
        return worst;
    }

    static int lowerBound(double[] a, double v) {
        int i = Arrays.binarySearch(a, v);
        if (i < 0) {
            return -i - 1;
        }
        while (i > 0 && a[i - 1] == v) {
            i--;
        }
        return i;
    }

    static int upperBound(double[] a, double v) {
        int i = Arrays.binarySearch(a, v);
        if (i < 0) {
            return -i - 1;
        }
        while (i < a.length && a[i] == v) {
            i++;
        }
        return i;
    }

    @Test
    void kllRankErrorIsUnderItsStatedBoundOnUniformNormalAndLogNormalValues() {
        assertThat(Kll.rankError(200)).isEqualTo(0.0165, org.assertj.core.data.Offset.offset(1e-12));
        for (String dist : List.of("uniform", "normal", "lognormal")) {
            double[] x = values(dist, 10_000, 11);
            Kll s = new Kll(200);
            for (double v : x) {
                s.update(v);
            }
            assertThat(s.n()).isEqualTo(10_000);
            assertThat(maxRankError(s, x)).as(dist).isLessThan(Kll.rankError(200));
            assertThat(s.quantile(0)).isEqualTo(Arrays.stream(x).min().orElseThrow());
            assertThat(s.quantile(1)).isEqualTo(Arrays.stream(x).max().orElseThrow());
        }
    }

    @Test
    void kllMergedInAnySplitStaysWithinItsBoundAndRepeatsToTheByteInAFixedOrder() {
        double[] x = values("lognormal", 10_000, 23);
        for (int parts : new int[] {2, 3, 7, 16}) {
            List<Kll> sk = new ArrayList<>();
            for (int p = 0; p < parts; p++) {
                Kll s = new Kll(200);
                for (int i = p; i < x.length; i += parts) {
                    s.update(x[i]);
                }
                sk.add(s);
            }
            Kll left = sk.get(0);
            for (int p = 1; p < parts; p++) {
                left = left.merge(sk.get(p));
            }
            Kll right = sk.get(parts - 1);
            for (int p = parts - 2; p >= 0; p--) {
                right = sk.get(p).merge(right);
            }
            assertThat(left.n()).isEqualTo(x.length);
            assertThat(maxRankError(left, x)).as(parts + " parts, left-deep").isLessThan(Kll.rankError(200));
            assertThat(maxRankError(right, x)).as(parts + " parts, right-deep").isLessThan(Kll.rankError(200));
            Kll again = sk.get(0);
            for (int p = 1; p < parts; p++) {
                again = again.merge(Kll.decode(sk.get(p).encode()));
            }
            assertThat(again.encode()).as("the same parts in the same order: the same bytes").isEqualTo(left.encode());
        }
    }

    @Test
    void kllFromSqlQuantilePointsAddsAtMostAQuarterOfOneOverK() {
        double[] x = values("normal", 10_000, 5);
        double[] sorted = x.clone();
        Arrays.sort(sorted);
        int kk = 400;
        List<Double> pts = new ArrayList<>();
        for (int j = 0; j <= kk; j++) {
            double q = (double) j / kk;
            int idx = (int) Math.max(0, Math.ceil(q * sorted.length) - 1);   // quantile_disc
            pts.add(sorted[Math.min(sorted.length - 1, idx)]);
        }
        Kll s = Kll.fromPoints(200, pts, x.length);
        assertThat(s.n()).isEqualTo(x.length);
        assertThat(maxRankError(s, x)).isLessThan(Kll.rankError(200) + 1.0 / (4 * 200));
    }

    @Test
    void spaceSavingFindsTheHeavyValuesNeverUnderCountsAndMergesCommutatively() {
        Random r = new Random(3);
        Map<String, Long> truth = new HashMap<>();
        SpaceSaving a = new SpaceSaving(100);
        SpaceSaving b = new SpaceSaving(100);
        for (int i = 0; i < 10_000; i++) {
            int z = (int) Math.floor(Math.pow(r.nextDouble(), 3) * 2_000);   // skewed towards small ids
            String v = "V" + z;
            truth.merge(v, 1L, Long::sum);
            (i % 2 == 0 ? a : b).add(v, 1);
        }
        SpaceSaving ab = a.merge(b);
        assertThat(ab.encode()).isEqualTo(b.merge(a).encode());
        assertThat(ab.n()).isEqualTo(10_000);
        List<Map.Entry<String, Long>> top = truth.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(10).toList();
        List<String> found = ab.top(10).stream().map(SpaceSaving.Counted::value).toList();
        for (Map.Entry<String, Long> t : top.subList(0, 5)) {
            assertThat(found).as("heavy value " + t.getKey()).contains(t.getKey());
        }
        for (SpaceSaving.Counted c : ab.top(50)) {
            long real = truth.getOrDefault(c.value(), 0L);
            assertThat(c.count()).as(c.value()).isGreaterThanOrEqualTo(real);
            assertThat(c.count() - real).as(c.value()).isLessThanOrEqualTo(c.error());
        }
        SpaceSaving exact = SpaceSaving.fromCounts(0, new ArrayList<>(truth.keySet()), new ArrayList<>(truth.values()));
        assertThat(exact.top(1).get(0).count()).isEqualTo(top.get(0).getValue());
    }

    @Test
    void exactOnRequestEqualsExactAndAutoAnswersBySketchAboveTheThreshold() {
        try (PivotFixture fx = new PivotFixture(2_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Map<String, Object> view = Map.of("source", "trades", "rows", List.of("REGION"), "values",
                    List.of(Map.of("measure", "approx_count_distinct(CPTY)", "as", "Cptys"), Map.of("measure", "count_distinct(CPTY)", "as", "Exact"),
                            Map.of("measure", "approx_median(PNL)", "as", "Med")));
            Viewer rita = Viewer.of("rita");
            fx.core.hooks(new ScalePolicy(1_000));             // the fixture's 2,000 rows are "large" here
            Map<String, Object> exact = fx.core.query(rita, PivotView.parse(view), new ScaleOptions(ScaleOptions.Exactness.EXACT, 0, false));
            Map<String, Object> auto = fx.core.query(rita, PivotView.parse(view), ScaleOptions.AUTO);
            for (Map<String, Object> cell : PivotCoreTest.cells(exact).values()) {
                assertThat(PivotCoreTest.val(cell, "Cptys")).isEqualTo(PivotCoreTest.val(cell, "Exact"));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> measures = (List<Map<String, Object>>) auto.get("measures");
            assertThat(measures).allSatisfy(m -> assertThat(m.get("kind")).isEqualTo("sketch"));
            assertThat(measures.get(0).get("error").toString()).contains("HyperLogLog").contains("4,096");
            Double approxGrand = null;
            Double exactGrand = null;
            for (Map<String, Object> cell : PivotCoreTest.cells(auto).values()) {
                if (((Map<?, ?>) cell.get("coords")).isEmpty()) {
                    approxGrand = PivotCoreTest.val(cell, "Exact");
                }
            }
            for (Map<String, Object> cell : PivotCoreTest.cells(exact).values()) {
                if (((Map<?, ?>) cell.get("coords")).isEmpty()) {
                    exactGrand = PivotCoreTest.val(cell, "Exact");
                }
            }
            assertThat(exactGrand).isEqualTo(fx.scalar("SELECT count(DISTINCT CPTY) FROM trades"));
            assertThat(Math.abs(approxGrand - exactGrand) / exactGrand).isLessThan(3 * Hll.relativeError(12));
            assertThat(String.valueOf(auto.get("notes"))).contains("≈ by its sketch").contains("approximate-above-rows");
        }
    }
}
