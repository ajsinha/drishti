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
import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.rows.RowsProvider;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A capped measure (PivotProvider.cap; the MR cube's stressed VaR and SBM, MR_CUBE_GUIDE.md section 5): the same view gets its figure on the
 * same cells whatever was asked before it or beside it (fresh, after other views filled the cache, in parallel), those cells are the view's
 * first in the grid's order (row by row as shown, each row's columns left to right), the provider computes those cells only, and a row's
 * diversification is never a sum of some of its parts.
 */
class CappedMeasureTest {

    /** Rows: DESK, CCY and X; median(X) is the capped measure (holistic, so a total has a diversification), sum(X) is not. */
    private static List<Object[]> rows() {
        List<Object[]> out = new ArrayList<>();
        String[] desks = {"A", "B", "C", "D"};
        String[] ccys = {"EUR", "USD", "JPY"};
        int i = 0;
        for (String d : desks) {
            for (String c : ccys) {
                for (int k = 0; k < 3; k++) {
                    out.add(new Object[] {d, c, 1.0 + (i++ * 7919 % 101)});
                }
            }
        }
        return out;
    }

    /** The rows provider, median(X) capped at {@code cap} cells, counting the cells it computes median for. */
    static final class Capped implements PivotProvider {
        final RowsProvider rows = new RowsProvider("inline", "Rows", List.of(new Field("DESK", "string", "DESK", ""),
                new Field("CCY", "string", "CCY", ""), new Field("X", "number", "X", "")), rows());
        final int cap;
        final AtomicInteger medianCells = new AtomicInteger();

        Capped(int cap) {
            this.cap = cap;
        }

        @Override
        public Optional<Cap> cap(String function) {
            return "median".equals(function) ? Optional.of(new Cap(cap, "median is computed for the first " + cap + " cells of a view"))
                    : Optional.empty();
        }

        @Override
        public Partials evaluate(Viewer v, AggregationPlan plan) {
            Partials p = rows.evaluate(v, plan);
            if (plan.functions().stream().anyMatch(f -> f.fn().name().equals("median"))) {
                p.sets().forEach(s -> medianCells.addAndGet(s.cells().size()));
            }
            return p;
        }

        @Override
        public String id() {
            return rows.id();
        }

        @Override
        public boolean serves(String source) {
            return rows.serves(source);
        }

        @Override
        public Map<String, String> sources(Viewer v) {
            return rows.sources(v);
        }

        @Override
        public Catalogue catalogue(Viewer v, String source, String date) {
            return rows.catalogue(v, source, date);
        }

        @Override
        public Capabilities capabilities() {
            return rows.capabilities();
        }

        @Override
        public Optional<AggFunction<Object>> nativeFunction(String name, List<String> args) {
            return rows.nativeFunction(name, args);
        }

        @Override
        public String version(Viewer v, String source, String date) {
            return rows.version(v, source, date);
        }

        @Override
        public Estimate estimate(Viewer v, AggregationPlan plan) {
            return rows.estimate(v, plan);
        }

        @Override
        public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
            return rows.drill(v, plan, key, offset, limit);
        }
    }

    private static PivotCore core(Capped p) {
        return new PivotCore(List.of(p), StateCache.lru(64L << 20), PivotCore.Settings.defaults());
    }

    private static PivotView view(List<String> rows, List<String> cols, String median) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", "inline");
        spec.put("rows", rows);
        spec.put("columns", cols);
        spec.put("values", List.of(Map.of("measure", median, "as", "Median"), Map.of("measure", "sum(X)", "as", "Sum")));
        return PivotView.parse(spec);
    }

    private static Map<String, Object> ask(PivotCore core, List<String> cols) {
        return core.query(Viewer.of("rita"), view(List.of("DESK"), cols, "median(X)"));
    }

    /** Each cell's figures and notes (what a person sees), by its grid key. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> seen(Map<String, Object> answer) {
        Map<String, Object> out = new TreeMap<>();
        ((Map<String, Map<String, Object>>) answer.get("cells")).forEach((k, c) -> out.put(k, List.of(c.get("values"),
                String.valueOf(c.get("notes")))));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> withMedian(Map<String, Object> answer) {
        List<String> out = new ArrayList<>();
        ((Map<String, Map<String, Object>>) answer.get("cells")).forEach((k, c) -> {
            if (((Map<String, Object>) c.get("values")).get("Median") != null) {
                out.add(k);
            }
        });
        return out;
    }

    @Test
    void theCapTakesTheFirstCellsInTheGridsOrderAndTheProviderComputesThoseOnly() {
        Capped p = new Capped(5);
        Map<String, Object> a = ask(core(p), List.of("CCY"));
        // rows A, B, C, D, then the grand total (subtotals below); columns EUR, JPY, USD, then the total
        assertThat(withMedian(a)).containsExactlyInAnyOrder("DESK=A|CCY=EUR", "DESK=A|CCY=JPY", "DESK=A|CCY=USD", "DESK=A|*", "DESK=B|CCY=EUR");
        assertThat(p.medianCells.get()).isEqualTo(5);
        @SuppressWarnings("unchecked")
        Map<String, Object> past = ((Map<String, Map<String, Object>>) a.get("cells")).get("*|*");
        assertThat(String.valueOf(past.get("notes"))).contains("median is computed for the first 5 cells of a view");
        assertThat(((Map<String, Object>) past.get("values")).get("Sum")).isNotNull();          // the uncapped measure is everywhere
    }

    @Test
    void theSameViewGetsTheSameCellsWhateverWasAskedBefore() {
        Map<String, Object> alone = ask(core(new Capped(5)), List.of("CCY"));
        PivotCore after = core(new Capped(5));
        ask(after, List.of());
        Map<String, Object> second = ask(after, List.of("CCY"));
        assertThat(seen(second)).isEqualTo(seen(alone));
        StateCache cache = StateCache.lru(64L << 20);             // a cache holding every cell's figure, from a view with a wider cap
        ask(new PivotCore(List.of(new Capped(40)), cache, PivotCore.Settings.defaults()), List.of("CCY"));
        PivotCore narrow = new PivotCore(List.of(new Capped(5)), cache, PivotCore.Settings.defaults());
        assertThat(seen(ask(narrow, List.of("CCY")))).isEqualTo(seen(alone));
        // cells cached by an earlier view never get a figure past the cap
        PivotCore warmed = core(new Capped(5));
        ask(warmed, List.of());
        ask(warmed, List.of("CCY"));
        Map<String, Object> third = ask(warmed, List.of("CCY"));
        assertThat(withMedian(third)).hasSize(5);
        assertThat(seen(third)).isEqualTo(seen(alone));
    }

    @Test
    void theSameViewGetsTheSameCellsWhenViewsAreAskedInParallel() throws Exception {
        Map<String, Object> alone = seen(ask(core(new Capped(5)), List.of("CCY")));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 10; round++) {
                PivotCore shared = core(new Capped(5));
                List<Future<Map<String, Object>>> fs = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    List<String> cols = i % 3 == 0 ? List.of("CCY") : i % 3 == 1 ? List.of() : List.of("CCY");
                    fs.add(pool.submit(() -> ask(shared, cols)));
                }
                for (int i = 0; i < fs.size(); i++) {
                    Map<String, Object> a = fs.get(i).get();
                    if (i % 3 != 1) {
                        assertThat(seen(a)).as("round " + round + ", ask " + i).isEqualTo(alone);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDiversificationIsNeverASumOfSomeOfTheParts() {
        // rows A..D and the total, no columns: five cells; with four computed the total has none, so no gap
        Map<String, Object> four = core(new Capped(4)).query(Viewer.of("rita"), view(List.of("DESK"), List.of(), "median(X)"));
        Map<String, Object> grand = ((List<Map<String, Object>>) four.get("rows")).stream().filter(r -> "grand".equals(r.get("kind")))
                .findFirst().orElseThrow();
        Map<String, Object> byColumn = (Map<String, Object>) grand.get("diversificationByColumn");
        assertThat((Map<String, Object>) byColumn.get("*")).containsEntry("Median", null);
        // all five computed: the gap is Σ parts − total
        Map<String, Object> five = core(new Capped(5)).query(Viewer.of("rita"), view(List.of("DESK"), List.of(), "median(X)"));
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) five.get("cells");
        double parts = 0;
        for (String d : List.of("A", "B", "C", "D")) {
            parts += ((Number) ((Map<String, Object>) cells.get("DESK=" + d + "|*").get("values")).get("Median")).doubleValue();
        }
        double total = ((Number) ((Map<String, Object>) cells.get("*|*").get("values")).get("Median")).doubleValue();
        Map<String, Object> g5 = ((List<Map<String, Object>>) five.get("rows")).stream().filter(r -> "grand".equals(r.get("kind"))).findFirst()
                .orElseThrow();
        assertThat(((Number) ((Map<String, Object>) g5.get("diversification")).get("Median")).doubleValue()).isEqualTo(parts - total);
    }

    @Test
    void aViewIsNotSortedOrRankedByACappedMeasure() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", "inline");
        spec.put("rows", List.of("DESK"));
        spec.put("values", List.of(Map.of("measure", "median(X)", "as", "Median")));
        spec.put("sort", Map.of("DESK", Map.of("by", "Median", "desc", true)));
        assertThatThrownBy(() -> core(new Capped(5)).query(Viewer.of("rita"), PivotView.parse(spec)))
                .hasMessageContaining("not sorted by it");
    }

    @Test
    void aViewOfCappedMeasuresOnlyStillHasItsCells() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("source", "inline");
        spec.put("rows", List.of("DESK"));
        spec.put("values", List.of(Map.of("measure", "median(X)", "as", "Median")));
        Capped p = new Capped(2);
        Map<String, Object> a = core(p).query(Viewer.of("rita"), PivotView.parse(spec));
        assertThat(withMedian(a)).containsExactly("DESK=A|*", "DESK=B|*");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        assertThat(cells).containsKeys("DESK=C|*", "DESK=D|*", "*|*");
        assertThat(p.medianCells.get()).isEqualTo(2);
    }
}
