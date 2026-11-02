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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.bicompute.sql.TabularSource;
import java.util.List;
import java.util.Map;

/**
 * Rollups over the tabular provider's sources: read as the system viewer (every row), the version the source states, the parts of a source
 * that lists its files ({@link Parted}: a direct lake route) so a load that appends files refreshes only theirs; a source that does not is
 * rebuilt whole when its version moves. A person is unrestricted when the source opened for them hides no field and has no row rule.
 */
public final class TabularRollupSource implements RollupSource {

    /**
     * A tabular source that lists its parts (the files of the version read) and reads only some of them: incremental refresh on load.
     */
    public interface Parted {
        List<Part> parts();

        /** The relation over {@code parts} only, the same columns as the whole. */
        TabularSource.Relation relation(DuckDbExecutor exec, List<String> parts);
    }

    private final TabularProvider provider;
    private final Viewer system;

    /** @param system the viewer that reads every row (the host's system identity) */
    public TabularRollupSource(TabularProvider provider, Viewer system) {
        this.provider = provider;
        this.system = system;
    }

    @Override
    public PivotProvider provider() {
        return provider;
    }

    @Override
    public boolean unrestricted(Viewer v, String source, String date) {
        TabularSource s = provider.resolver().open(v, source, date);
        if (s.masked() > 0) {
            return false;
        }
        TabularSource.Relation r = s.relation(provider.executor());
        return r.rowRules() == null || r.rowRules().isBlank();
    }

    @Override
    public String dataVersion(String source, String date) {
        return provider.resolver().open(system, source, date).version();
    }

    @Override
    public String latestDate(String source) {
        return provider.resolver().open(system, source, null).date();
    }

    @Override
    public List<Part> parts(String source, String date) {
        TabularSource s = provider.resolver().open(system, source, date);
        return s instanceof Parted p ? p.parts() : List.of();
    }

    @Override
    public Partials evaluate(AggregationPlan plan, List<String> parts) {
        return evaluateAs(system, plan, parts);
    }

    @Override
    public Partials evaluateAs(Viewer viewer, AggregationPlan plan, List<String> parts) {
        if (parts == null) {
            return provider.evaluate(viewer, plan);
        }
        TabularSource.Resolver base = provider.resolver();
        TabularSource.Resolver only = new TabularSource.Resolver() {
            @Override
            public Map<String, String> sources(Viewer v) {
                return base.sources(v);
            }

            @Override
            public boolean serves(String source) {
                return base.serves(source);
            }

            @Override
            public TabularSource open(Viewer v, String source, String date) {
                TabularSource s = base.open(v, source, date);
                if (!(s instanceof Parted p)) {
                    throw new IllegalStateException(source + " lists no parts");
                }
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
                        return s.version() + "|parts " + parts.size();
                    }

                    @Override
                    public long rows() {
                        return -1;
                    }

                    @Override
                    public long bytes() {
                        return -1;
                    }

                    @Override
                    public Map<String, Object> notes() {
                        return s.notes();
                    }

                    @Override
                    public Relation relation(DuckDbExecutor exec) {
                        return p.relation(exec, parts);
                    }
                };
            }
        };
        return new TabularProvider(provider.executor(), only).evaluate(viewer, plan);
    }
}
