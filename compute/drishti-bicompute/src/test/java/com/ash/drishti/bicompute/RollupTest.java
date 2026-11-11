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
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.cluster.OrderedMerge;
import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.Partials;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.rollup.AggregateAwareness;
import com.ash.drishti.bicompute.rollup.Rollup;
import com.ash.drishti.bicompute.rollup.RollupDef;
import com.ash.drishti.bicompute.rollup.RollupSource;
import com.ash.drishti.bicompute.rollup.Rollups;
import com.ash.drishti.bicompute.rollup.TabularRollupSource;
import com.ash.drishti.bicompute.scale.ScalePolicy;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rollups of partial states and aggregate awareness (ARCHITECTURE.md section 26.6): a view answered from a rollup equals the same view read
 * from the table (every built-in that merges, the sketch's registers exactly, a month from a grain of days); the provider is not asked; the
 * smallest covering rollup is picked; a person with row rules is never answered from a rollup; a holistic function is refused as rollup
 * material; an incremental refresh (new parts merged, a restated partition rebuilt) equals a rebuild; a rollup read back from disk answers
 * the same.
 */
class RollupTest {

    final PivotFixture fx = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults());
    final PivotFixture plain = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults());
    final Viewer rita = Viewer.of("rita");

    @TempDir
    Path dir;

    @AfterEach
    void close() {
        fx.close();
        plain.close();
    }

    static final List<Object> MERGEABLE = List.of(Map.of("measure", "sum(NOTIONAL)", "as", "Notional"), Map.of("measure", "count()", "as", "Trades"),
            Map.of("measure", "avg(PNL)", "as", "Avg"), Map.of("measure", "min(PNL)", "as", "Min"), Map.of("measure", "max(PNL)", "as", "Max"),
            Map.of("measure", "var_samp(PNL)", "as", "Var"), Map.of("measure", "wavg(PNL, NOTIONAL)", "as", "WAvg"),
            Map.of("measure", "distinct(CPTY)", "as", "Cptys"));

    /** The rollup keeps the distinct count's sketch ("≈"): the views answer it by its sketch (the scale policy, any size here). */
    static final List<String> FNS = List.of("sum(NOTIONAL)", "count()", "avg(PNL)", "min(PNL)", "max(PNL)", "var_samp(PNL)", "wavg(PNL, NOTIONAL)",
            "distinct(CPTY) ≈");

    {
        plain.core.hooks(new ScalePolicy(1));
    }

    Rollups rollups(PivotFixture f, Path d) {
        Rollups r = new Rollups(Map.of("tabular", new TabularRollupSource(f.provider, Viewer.of("system"))), d);
        f.core.hooks(new ScalePolicy(1));
        f.core.hooks(new AggregateAwareness(r, 10));
        return r;
    }

    Map<String, Object> view(Object rows, Object cols, Map<String, Object> filters) {
        return Map.of("source", "trades", "rows", rows, "columns", cols, "values", MERGEABLE, "filters", filters, "expandTo", Map.of("rows", 2));
    }

    static void sameCells(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Map<String, Object>> x = PivotCoreTest.cells(a);
        Map<String, Map<String, Object>> y = PivotCoreTest.cells(b);
        assertThat(x.keySet()).isEqualTo(y.keySet());
        for (String k : x.keySet()) {
            assertThat(x.get(k).get("members")).as(k).isEqualTo(y.get(k).get("members"));
            for (String m : List.of("Notional", "Trades", "Avg", "Min", "Max", "Var", "WAvg", "Cptys")) {
                Double u = PivotCoreTest.val(x.get(k), m);
                Double w = PivotCoreTest.val(y.get(k), m);
                if (u == null) {
                    assertThat(w).as(k + " " + m).isNull();
                } else {
                    assertThat(w).as(k + " " + m).isCloseTo(u, offset(Math.max(1e-9, Math.abs(u) * 1e-11)));
                }
            }
        }
    }

    @Test
    void aViewAnsweredFromARollupEqualsTheTableAndTheProviderIsNotAsked() {
        Rollups r = rollups(fx, dir);
        r.define(new RollupDef("by-day", "trades", List.of("REGION", "DESK", "CCY", "TRADE_DATE"), FNS, "latest", null));
        assertThat(r.refresh("by-day", "2026-10-30")).contains("rebuilt whole");
        for (Map<String, Object> v : List.of(view(List.of("REGION", "DESK"), List.of("CCY"), Map.of()),
                view(List.of("DESK"), List.of("TRADE_DATE:month"), Map.of("REGION", Map.of("in", List.of("EMEA", "APAC")))),
                view(List.of("TRADE_DATE:quarter", "REGION"), List.of(), Map.of("CCY", Map.of("notIn", List.of("JPY")))))) {
            int before = fx.statements.size();
            Map<String, Object> fromRollup = fx.core.query(rita, PivotView.parse(v));
            assertThat(fx.statements).as("no SQL ran: the rollup answered").hasSize(before);
            assertThat(String.valueOf(fromRollup.get("notes"))).contains("answered from the rollup by-day");
            sameCells(plain.core.query(rita, PivotView.parse(v)), fromRollup);
        }
    }

    @Test
    void theSmallestCoveringRollupIsPickedAndAViewItDoesNotCoverReadsTheTable() {
        Rollups r = rollups(fx, dir);
        r.define(new RollupDef("fine", "trades", List.of("REGION", "DESK", "CCY", "TRADE_DATE"), FNS, "latest", null));
        r.define(new RollupDef("coarse", "trades", List.of("REGION", "DESK"), List.of("sum(NOTIONAL)", "count()"), "latest", null));
        r.refresh("fine", "2026-10-30");
        r.refresh("coarse", "2026-10-30");
        Map<String, Object> small = Map.of("source", "trades", "rows", List.of("REGION", "DESK"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")), "expandTo", Map.of("rows", 2));
        assertThat(String.valueOf(fx.core.query(rita, PivotView.parse(small)).get("notes"))).contains("rollup coarse");
        Map<String, Object> needsFine = Map.of("source", "trades", "rows", List.of("REGION"), "columns", List.of("CCY"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")));
        assertThat(String.valueOf(fx.core.query(rita, PivotView.parse(needsFine)).get("notes"))).contains("rollup fine");
        int before = fx.statements.size();
        Map<String, Object> notCovered = Map.of("source", "trades", "rows", List.of("CPTY"), "values", List.of(Map.of("measure", "sum(NOTIONAL)",
                "as", "N")));
        assertThat(String.valueOf(fx.core.query(rita, PivotView.parse(notCovered)).get("notes"))).doesNotContain("rollup");
        assertThat(fx.statements.size()).isGreaterThan(before);
        Map<String, Object> estimate = fx.core.estimate(rita, PivotView.parse(small));
        assertThat(estimate.get("rollup")).isEqualTo("coarse");
    }

    @Test
    void aPersonWithRowRulesIsNeverAnsweredFromARollupAndHolisticFunctionsAreRefused() {
        Rollups r = rollups(fx, dir);
        r.define(new RollupDef("by-day", "trades", List.of("REGION", "DESK", "CCY", "TRADE_DATE"), FNS, "latest", null));
        r.refresh("by-day", "2026-10-30");
        Map<String, Object> v = Map.of("source", "trades", "rows", List.of("DESK"), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N")));
        Map<String, Object> limited = fx.core.query(Viewer.of("limited-lee"), PivotView.parse(v));
        assertThat(String.valueOf(limited.get("notes"))).doesNotContain("rollup");
        assertThatThrownBy(() -> r.define(new RollupDef("bad", "trades", List.of("DESK"), List.of("median(PNL)"), "latest", null)))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("holistic");
    }

    @Test
    void aRollupReadBackFromDiskAnswersTheSame() {
        Rollups r = rollups(fx, dir);
        r.define(new RollupDef("by-day", "trades", List.of("REGION", "DESK", "CCY", "TRADE_DATE"), FNS, "latest", null));
        r.refresh("by-day", "2026-10-30");
        Map<String, Object> v = view(List.of("REGION"), List.of("TRADE_DATE:year"), Map.of());
        Map<String, Object> first = fx.core.query(rita, PivotView.parse(v));
        try (PivotFixture again = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults())) {
            Rollups r2 = rollups(again, dir);
            r2.define(new RollupDef("by-day", "trades", List.of("REGION", "DESK", "CCY", "TRADE_DATE"), FNS, "latest", null));
            assertThat(r2.all()).hasSize(1);
            Map<String, Object> second = again.core.query(rita, PivotView.parse(v));
            assertThat(String.valueOf(second.get("notes"))).contains("rollup by-day");
            sameCells(first, second);
        }
    }

    /** A rollup source whose "parts" are row shards of the fixture's table, its version and parts set by the test (loads and restatements). */
    static final class ShardParts implements RollupSource {
        final TabularRollupSource d;
        String version = "v1";
        List<Part> parts = new ArrayList<>();
        int evaluated;

        ShardParts(TabularRollupSource d) {
            this.d = d;
        }

        @Override
        public PivotProvider provider() {
            return d.provider();
        }

        @Override
        public boolean unrestricted(Viewer v, String source, String date) {
            return d.unrestricted(v, source, date);
        }

        @Override
        public String dataVersion(String source, String date) {
            return version;
        }

        @Override
        public String latestDate(String source) {
            return "2026-10-30";
        }

        @Override
        public List<Part> parts(String source, String date) {
            return parts;
        }

        @Override
        public Partials evaluate(AggregationPlan plan, List<String> ids) {
            List<Partials> got = new ArrayList<>();
            for (String id : ids == null ? parts.stream().map(Part::id).toList() : ids) {
                int i = Integer.parseInt(id.substring(1, 2));                 // "s0", "s1", "s2", or "s1b" (a restated s1: the same rows)
                got.add(d.evaluate(plan.on(new AggregationPlan.Shard(i, 3)), null));
                evaluated++;
            }
            return OrderedMerge.merge(plan, got);
        }
    }

    @Test
    void anIncrementalRefreshMergesNewPartsRebuildsARestatedPartitionAndEqualsARebuild() {
        ShardParts src = new ShardParts(new TabularRollupSource(fx.provider, Viewer.of("system")));
        Rollups r = new Rollups(Map.of("tabular", src), null);
        fx.core.hooks(new ScalePolicy(1));
        fx.core.hooks(new AggregateAwareness(r, 10));
        r.define(new RollupDef("by-day", "trades", List.of("REGION", "DESK", "CCY", "TRADE_DATE"), FNS, "latest", null));
        src.parts = new ArrayList<>(List.of(new RollupSource.Part("s0", "A", -1, -1), new RollupSource.Part("s1", "A", -1, -1)));
        r.refresh("by-day", "2026-10-30");
        assertThat(src.evaluated).isEqualTo(2);
        src.version = "v2";                                                    // a load appends s2 in partition B
        src.parts.add(new RollupSource.Part("s2", "B", -1, -1));
        assertThat(r.refresh("by-day", "2026-10-30")).contains("1 partition(s) built or rebuilt");
        assertThat(src.evaluated).as("only the new part was read").isEqualTo(3);
        Map<String, Object> v = view(List.of("REGION", "DESK"), List.of("TRADE_DATE:month"), Map.of());
        sameCells(plain.core.query(rita, PivotView.parse(v)), fx.core.query(rita, PivotView.parse(v)));
        src.version = "v3";                                                    // a restatement replaces s1 in A
        src.parts.set(1, new RollupSource.Part("s1b", "A", -1, -1));
        assertThat(r.refresh("by-day", "2026-10-30")).contains("1 partition(s) built or rebuilt");
        assertThat(src.evaluated).as("partition A rebuilt from its two parts; B untouched").isEqualTo(5);
        sameCells(plain.core.query(rita, PivotView.parse(v)), fx.core.query(rita, PivotView.parse(v)));
        Rollup rolled = r.all().get(0);
        assertThat(rolled.partitions()).containsOnlyKeys("A", "B");
        src.version = "v4";                                                    // a load appends s2 again into B: merged, not rebuilt
        src.parts.set(2, new RollupSource.Part("s2", "B", -1, -1));
        assertThat(r.refresh("by-day", "2026-10-30")).contains("0 new part(s) merged");
    }
}
