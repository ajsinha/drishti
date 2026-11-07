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
package com.ash.drishti.bicompute.sql;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The tabular sources read through a sample (ARCHITECTURE.md section 23.9.8, the first answer of a progressive pair): the relation is wrapped in
 * DuckDB's {@code USING SAMPLE p PERCENT}, by row (Bernoulli) on small relations and by vector (system: whole chunks skipped, so less is
 * read) on large ones, with a fixed seed so the same view samples the same rows. The person's row rules still apply (to the sampled rows,
 * before any grouping); the version says "sampled", so nothing sampled is ever taken for exact.
 */
public final class SampledResolver implements TabularSource.Resolver {

    /** Above this many rows the sample is by vector (system), below it by row (Bernoulli). */
    static final long SYSTEM_ABOVE_ROWS = 10_000_000L;
    static final int SEED = 20_261_102;

    private final TabularSource.Resolver d;
    private final double fraction;

    public SampledResolver(TabularSource.Resolver d, double fraction) {
        this.d = d;
        this.fraction = fraction;
    }

    /** A hook giving the core a sampling tabular provider for its ≈ first answers. */
    public static PivotCore.Hooks hook() {
        return new PivotCore.Hooks() {
            @Override
            public Optional<PivotProvider> sampler(PivotProvider p, double f) {
                if (p instanceof TabularProvider tp) {
                    return Optional.of(new TabularProvider(tp.executor(), new SampledResolver(tp.resolver(), f)));
                }
                return Optional.empty();
            }
        };
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return d.sources(v);
    }

    @Override
    public boolean serves(String source) {
        return d.serves(source);
    }

    @Override
    public TabularSource open(Viewer v, String source, String date) {
        TabularSource s = d.open(v, source, date);
        String method = s.rows() > SYSTEM_ABOVE_ROWS ? "system" : "bernoulli";
        String pct = String.format(Locale.ROOT, "%.6f", fraction * 100);
        return new TabularSource() {
            @Override
            public String id() {
                return s.id();
            }

            @Override
            public String title() {
                return s.title();
            }

            @Override
            public List<PivotProvider.Field> fields() {
                return s.fields();
            }

            @Override
            public int masked() {
                return s.masked();
            }

            @Override
            public String date() {
                return s.date();
            }

            @Override
            public List<String> dates() {
                return s.dates();
            }

            @Override
            public String version() {
                return s.version() + "|sampled " + pct + "%";
            }

            @Override
            public long rows() {
                return s.rows() < 0 ? -1 : Math.round(s.rows() * fraction);
            }

            @Override
            public long bytes() {
                return s.bytes() < 0 ? -1 : Math.round(s.bytes() * fraction);
            }

            @Override
            public Map<String, Object> notes() {
                return s.notes();
            }

            @Override
            public Relation relation(DuckDbExecutor exec) {
                return sampled(s.relation(exec));
            }

            /** A document plan's relation (its lists unnested) sampled the same way: its rows, not its documents. */
            @Override
            public Relation relation(DuckDbExecutor exec, com.ash.drishti.bicompute.core.AggregationPlan plan) {
                return sampled(s.relation(exec, plan));
            }

            @Override
            public com.ash.drishti.bicompute.core.PivotView prepare(com.ash.drishti.bicompute.core.PivotView view) {
                return s.prepare(view);
            }

            private Relation sampled(Relation r) {
                String from = "(SELECT * FROM " + r.from() + " AS \"s\" USING SAMPLE " + pct + " PERCENT (" + method + ", " + SEED + "))";
                return new Relation(from, r.params(), r.rowRules(), r.ruleParams());
            }
        };
    }
}
