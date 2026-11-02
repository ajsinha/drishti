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
package com.ash.drishti.server.cube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The shared cube core on a hand-made source (no VaR engine: the SPI as the counterparty cube will use it). Four members, two desks × two
 * currencies, each a vector of five scenarios; the "worst loss" measure (a statistic of the summed vector, non-additive) and the sum of the
 * members' sizes (additive). The core never sums a non-additive figure: every cell and total is the statistic of its own summed vector, worked
 * here by hand; the additive one adds up; the sets are asked of the source in one batch, then from the cache; the budget refuses a view too
 * large with its count; a nested axis and a filter.
 */
class CubeEngineTest {

    static final double[][] V = {
            {-10, 2, 3, -1, 0},        // m0: desk A, USD
            {4, -6, 1, 2, -2},         // m1: desk A, EUR
            {3, 5, -8, -1, 1},         // m2: desk B, USD
            {-2, 1, 4, -7, 3}};        // m3: desk B, EUR
    static final String[] DESK = {"A", "A", "B", "B"};
    static final String[] CCY = {"USD", "EUR", "USD", "EUR"};

    /** The hand-made source: its batches counted. */
    static final class Fake implements CubeSource<double[]> {
        final AtomicInteger batches = new AtomicInteger();
        final AtomicInteger sets = new AtomicInteger();

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String title() {
            return "test cube";
        }

        @Override
        public List<CubeMeasure> measures() {
            return List.of(new CubeMeasure("WORST", "Worst loss", CubeMeasure.Kind.VECTOR, "money", "minus the smallest summed value", ""),
                    new CubeMeasure("SIZE", "Size", CubeMeasure.Kind.ADDITIVE, "count", "members", ""));
        }

        @Override
        public List<String> defaultMeasures() {
            return List.of("WORST");
        }

        @Override
        public String drillBelow(String dimension) {
            return "DESK".equals(dimension) ? "CCY" : null;
        }

        @Override
        public List<LocalDate> historyDates(Principal p, CubeView view, int n) {
            return List.of();
        }

        @Override
        public Universe<double[]> open(Principal p, CubeView view) {
            return new Universe<>() {
                @Override
                public LocalDate date() {
                    return LocalDate.of(2026, 9, 30);
                }

                @Override
                public String canonical() {
                    return "ALL";
                }

                @Override
                public OrdinalSet members() {
                    return OrdinalSet.range(0, 4);
                }

                @Override
                public List<CubeDimension> dimensions() {
                    return List.of(new CubeDimension("DESK", "desk", "attribute", ""), new CubeDimension("CCY", "currency", "attribute", ""));
                }

                @Override
                public Map<String, OrdinalSet> partition(String d) {
                    String[] col = switch (d) {
                        case "DESK" -> DESK;
                        case "CCY" -> CCY;
                        default -> throw CubeView.bad(d + " is not a dimension");
                    };
                    Map<String, OrdinalSet.Builder> b = new java.util.TreeMap<>();
                    for (int i = 0; i < 4; i++) {
                        b.computeIfAbsent(col[i], k -> new OrdinalSet.Builder()).add(i);
                    }
                    Map<String, OrdinalSet> out = new LinkedHashMap<>();
                    b.forEach((k, v) -> out.put(k, v.build()));
                    return out;
                }

                @Override
                public String label(String dimension, String value) {
                    return value;
                }

                @Override
                public String scopeOf(Map<String, String> coords) {
                    return coords.isEmpty() ? "ALL" : coords.toString();
                }

                @Override
                public String cacheKey() {
                    return "v1";
                }

                @Override
                public List<double[]> aggregates(List<OrdinalSet> sets) {
                    batches.incrementAndGet();
                    Fake.this.sets.addAndGet(sets.size());
                    List<double[]> out = new ArrayList<>();
                    for (OrdinalSet s : sets) {
                        double[] acc = new double[5];
                        s.forEach(i -> {
                            for (int j = 0; j < 5; j++) {
                                acc[j] += V[i][j];
                            }
                        });
                        out.add(acc);
                    }
                    return out;
                }

                @Override
                public long bytes(double[] a) {
                    return 8L * a.length;
                }

                @Override
                public Figures figures(List<String> measures, double[] a, Cell cell, double[] total) {
                    Map<String, Double> v = new LinkedHashMap<>();
                    double min = Double.POSITIVE_INFINITY;
                    for (double x : a) {
                        min = Math.min(min, x);
                    }
                    v.put("WORST", -min);
                    v.put("SIZE", (double) cell.set().size());
                    return new Figures(v, Map.of());
                }

                @Override
                public Map<String, Object> detail(double[] a, Cell cell) {
                    return Map.of("values", a);
                }

                @Override
                public Map<String, Object> provenance() {
                    return Map.of("source", "hand-made");
                }

                @Override
                public Map<String, Object> headline(double[] total) {
                    return Map.of();
                }
            };
        }
    }

    static CubeEngine engine(Fake f, int maxCells) {
        return new CubeEngine(new CubeProperties(maxCells, null, null, null, null, null), List.of(f));
    }

    static final Principal ME = new Principal("me", List.of("risk"));

    @SuppressWarnings("unchecked")
    static double value(Object entry, String m) {
        return ((Number) ((Map<String, Object>) ((Map<String, Object>) entry).get("values")).get(m)).doubleValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyTotalIsItsOwnSetsStatisticNeverASumAndTheAdditiveMeasureAddsUp() {
        Fake f = new Fake();
        CubeEngine e = engine(f, 100);
        Map<String, Object> a = e.query(ME, new CubeView("test", "ALL", List.of("DESK"), List.of("CCY"), List.of("WORST", "SIZE"), Map.of(), null,
                Map.of(), null, 0));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
        assertThat(rows).extracting(r -> r.get("key")).containsExactly("A", "B");
        assertThat(cols).extracting(r -> r.get("key")).containsExactly("EUR", "USD");
        // by hand: desk A = m0 + m1 = {-6, -4, 4, 1, -2}: worst 6; desk B = m2 + m3 = {1, 6, -4, -8, 4}: worst 8
        assertThat(value(rows.get(0), "WORST")).isEqualTo(6.0);
        assertThat(value(rows.get(1), "WORST")).isEqualTo(8.0);
        // EUR = m1 + m3 = {2, -5, 5, -5, 1}: 5; USD = m0 + m2 = {-7, 7, -5, -2, 1}: 7
        assertThat(value(cols.get(0), "WORST")).isEqualTo(5.0);
        assertThat(value(cols.get(1), "WORST")).isEqualTo(7.0);
        // the whole = {-5, 2, 0, -7, 2}: worst 7, not 6 + 8 nor 5 + 7
        assertThat(value(a.get("total"), "WORST")).isEqualTo(7.0);
        Map<String, Object> div = (Map<String, Object>) ((Map<String, Object>) a.get("diversification")).get("WORST");
        assertThat(div.get("totalAcrossRows")).isEqualTo(7.0);                  // 6 + 8 − 7
        assertThat(div.get("totalAcrossColumns")).isEqualTo(5.0);               // 5 + 7 − 7
        assertThat(((Map<String, Object>) a.get("diversification")).get("SIZE")).as("an additive measure has none").isNull();
        assertThat(value(a.get("total"), "SIZE")).isEqualTo(value(rows.get(0), "SIZE") + value(rows.get(1), "SIZE"));
        // one batch of 1 + 2 + 2 + 4 sets; asked again, all from the cache
        assertThat(f.batches.get()).isEqualTo(1);
        assertThat(f.sets.get()).isEqualTo(9);
        Map<String, Object> again = e.query(ME, new CubeView("test", "ALL", List.of("DESK"), List.of("CCY"), List.of("WORST"), Map.of(), null,
                Map.of(), null, 0));
        assertThat(again.get("fromCache")).isEqualTo(9);
        assertThat(f.batches.get()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNestedAxisAFilterADrillAndSorting() {
        Fake f = new Fake();
        CubeEngine e = engine(f, 100);
        Map<String, Object> nested = e.query(ME, CubeView.parse("MCUBE ALL BY DESK>CCY MEASURE WORST SORT desc", "test"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) nested.get("rows");
        assertThat(rows).hasSize(4);
        assertThat(value(rows.get(0), "WORST")).isEqualTo(10.0);                // m0 alone: its worst, -10
        Map<String, Object> filtered = e.query(ME, CubeView.parse("MCUBE ALL BY DESK MEASURE WORST FILTER CCY=USD", "test"));
        assertThat(value(filtered.get("total"), "WORST")).isEqualTo(7.0);       // USD only
        Map<String, Object> cell = e.cell(ME, CubeView.parse("MCUBE ALL BY DESK MEASURE WORST", "test"), "B", null);
        Map<String, Object> drill = (Map<String, Object>) cell.get("drill");
        assertThat(drill.get("dimension")).isEqualTo("CCY");
        assertThat((List<?>) drill.get("rows")).hasSize(2);
    }

    @Test
    void aViewOverTheBudgetIsRefusedWithItsCount() {
        CubeEngine e = engine(new Fake(), 8);
        assertThatThrownBy(() -> e.query(ME, CubeView.parse("MCUBE ALL BY DESK,CCY", "test"))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("9 cells").hasMessageContaining("budget of 8");
        assertThatThrownBy(() -> e.query(ME, CubeView.parse("MCUBE ALL BY DESK,CCY", "ccr"))).hasMessageContaining("not running");
    }

    @Test
    void theViewTextRoundTrips() {
        CubeView v = CubeView.parse("MCUBE DESK:DESK-RATES BY TT_BOOK>POSITION,CURRENCY MEASURE VAR,ES FILTER LEGAL_ENTITY=LE-NY|LE-LDN H 10D SUM "
                + "C 97.5 DATE 2026-09-30", "mr");
        assertThat(v.scope()).isEqualTo("DESK:DESK-RATES");
        assertThat(v.rows()).containsExactly("TT_BOOK", "POSITION");
        assertThat(v.columns()).containsExactly("CURRENCY");
        assertThat(v.filters().get("LEGAL_ENTITY")).containsExactly("LE-NY", "LE-LDN");
        assertThat(v.lens()).containsEntry("horizon", "10d").containsEntry("method", "sum").containsEntry("confidence", "97.5");
        assertThat(CubeView.parse(v.text(), "mr")).isEqualTo(v);
        assertThatThrownBy(() -> CubeView.parse("MCUBE BANK BY DESK,DESK", "mr")).hasMessageContaining("twice");
    }
}
