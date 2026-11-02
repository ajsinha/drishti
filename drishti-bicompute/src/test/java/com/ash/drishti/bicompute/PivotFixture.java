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

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.bicompute.sql.TabularSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A small seeded trades table (2,000 rows: region, desk, currency, trade date, notional, P&amp;L, a counterparty and a masked salary column)
 * staged in a DuckDB executor, a resolver over it (a person in role {@code limited} has SALARY masked and sees only region EMEA through a
 * row rule), and a core with the module's own cache.
 */
final class PivotFixture implements AutoCloseable {

    static final String[] REGIONS = {"AMERICAS", "APAC", "EMEA"};
    static final String[] DESKS = {"RATES", "FX", "CREDIT", "EQUITY", "COMMOD"};
    static final String[] CCYS = {"USD", "EUR", "GBP", "JPY"};

    final DuckDbExecutor exec = new DuckDbExecutor(DuckDbExecutor.Settings.small());
    final TabularProvider provider;
    final PivotCore core;
    final List<String> statements = new ArrayList<>();

    PivotFixture(int rows, StateCache cache, PivotCore.Settings settings) {
        List<DuckDbExecutor.Column> cols = List.of(new DuckDbExecutor.Column("REGION", "text"), new DuckDbExecutor.Column("DESK", "text"),
                new DuckDbExecutor.Column("CCY", "text"), new DuckDbExecutor.Column("TRADE_DATE", "text"),
                new DuckDbExecutor.Column("NOTIONAL", "number"), new DuckDbExecutor.Column("PNL", "number"),
                new DuckDbExecutor.Column("CPTY", "text"), new DuckDbExecutor.Column("SALARY", "number"));
        exec.stage("trades", cols, sink -> {
            Random r = new Random(42);
            for (int i = 0; i < rows; i++) {
                String region = REGIONS[r.nextInt(REGIONS.length)];
                String desk = region.equals("APAC") && r.nextBoolean() ? "FX" : DESKS[r.nextInt(DESKS.length)];
                sink.row(new Object[] {region, desk, CCYS[r.nextInt(CCYS.length)], "2026-" + String.format("%02d", 1 + r.nextInt(10)) + "-"
                        + String.format("%02d", 1 + r.nextInt(28)), Math.round(r.nextDouble() * 1e7) / 100.0, i % 97 == 0 ? null
                                : Math.round((r.nextGaussian() * 5e4) * 100) / 100.0, "CP-" + r.nextInt(150), 1000.0 + r.nextInt(500)});
            }
            return rows;
        });
        TabularSource.Resolver resolver = new TabularSource.Resolver() {
            @Override
            public Map<String, String> sources(Viewer v) {
                return Map.of("trades", "Trades");
            }

            @Override
            public boolean serves(String source) {
                return "trades".equals(source);
            }

            @Override
            public TabularSource open(Viewer v, String source, String date) {
                boolean limited = v.user().startsWith("limited");
                return new TabularSource() {
                    @Override
                    public String id() {
                        return "trades";
                    }

                    @Override
                    public String title() {
                        return "Trades";
                    }

                    @Override
                    public List<PivotProvider.Field> fields() {
                        List<PivotProvider.Field> f = new ArrayList<>(List.of(new PivotProvider.Field("REGION", "string", "region", ""),
                                new PivotProvider.Field("DESK", "string", "desk", ""), new PivotProvider.Field("CCY", "string", "currency", ""),
                                new PivotProvider.Field("TRADE_DATE", "date", "trade date", ""),
                                new PivotProvider.Field("NOTIONAL", "number", "notional", ""), new PivotProvider.Field("PNL", "number", "P&L", ""),
                                new PivotProvider.Field("CPTY", "string", "counterparty", "")));
                        if (!limited) {
                            f.add(new PivotProvider.Field("SALARY", "number", "salary", ""));
                        }
                        return f;
                    }

                    @Override
                    public int masked() {
                        return limited ? 1 : 0;
                    }

                    @Override
                    public String date() {
                        return "2026-10-30";
                    }

                    @Override
                    public List<String> dates() {
                        return List.of("2026-10-30");
                    }

                    @Override
                    public String version() {
                        return "v1|" + (limited ? "limited" : "all");
                    }

                    @Override
                    public long rows() {
                        return rows;
                    }

                    @Override
                    public long bytes() {
                        return rows * 64L;
                    }

                    @Override
                    public Map<String, Object> notes() {
                        return new LinkedHashMap<>(Map.of("route", "staged"));
                    }

                    @Override
                    public Relation relation(DuckDbExecutor e) {
                        if (limited) {
                            return new Relation("(SELECT REGION, DESK, CCY, TRADE_DATE, NOTIONAL, PNL, CPTY FROM \"trades\")", List.of(),
                                    "\"t\".\"REGION\" = ?", List.of("EMEA"));
                        }
                        return new Relation("\"trades\"", List.of(), null, List.of());
                    }
                };
            }
        };
        TabularProvider base = new TabularProvider(exec, resolver);
        this.provider = base;
        PivotProvider counting = new Counting(base, statements);
        this.core = new PivotCore(List.of(counting), cache, settings);
    }

    /** One number from a naive query. */
    Double scalar(String sql, Object... params) {
        return exec.query(sql, List.of(params), rs -> {
            if (!rs.next()) {
                return null;
            }
            Object o = rs.getObject(1);
            return o instanceof Number n ? n.doubleValue() : null;
        });
    }

    @Override
    public void close() {
        exec.close();
    }

    /** A provider that records the statements its delegate runs (lazy expansion is proven by what was asked). */
    static final class Counting implements PivotProvider {
        final PivotProvider d;
        final List<String> statements;

        Counting(PivotProvider d, List<String> statements) {
            this.d = d;
            this.statements = statements;
        }

        @Override
        public String id() {
            return d.id();
        }

        @Override
        public boolean serves(String source) {
            return d.serves(source);
        }

        @Override
        public Map<String, String> sources(Viewer v) {
            return d.sources(v);
        }

        @Override
        public Catalogue catalogue(Viewer v, String source, String date) {
            return d.catalogue(v, source, date);
        }

        @Override
        public Capabilities capabilities() {
            return d.capabilities();
        }

        @Override
        public String version(Viewer v, String source, String date) {
            return d.version(v, source, date);
        }

        @Override
        public Estimate estimate(Viewer v, com.ash.drishti.bicompute.core.AggregationPlan plan) {
            return d.estimate(v, plan);
        }

        @Override
        public com.ash.drishti.bicompute.core.Partials evaluate(Viewer v, com.ash.drishti.bicompute.core.AggregationPlan plan) {
            com.ash.drishti.bicompute.core.Partials p = d.evaluate(v, plan);
            statements.addAll(p.stats().statements());
            return p;
        }

        @Override
        public Rows drill(Viewer v, com.ash.drishti.bicompute.core.AggregationPlan plan, com.ash.drishti.bicompute.core.CellKey key, int offset,
                int limit) {
            return d.drill(v, plan, key, offset, limit);
        }
    }

    static Set<String> set(String... s) {
        return Set.of(s);
    }
}
