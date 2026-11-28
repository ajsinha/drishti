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
package com.ash.drishti.banking.var;

import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.compute.vector.MappedMatrix;
import com.ash.drishti.kernel.api.services.VectorViews;
import com.ash.engine.FloatVectors;
import com.ash.engine.Lease;
import com.ash.engine.RowSet;
import java.nio.FloatBuffer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The zero-copy handover of the VaR rows to the market-risk engine (R2_CUT_PLAN 3a and MC-12; behind
 * {@code drishti.module.banking.var.zero-copy}, off as shipped): when the engine asks the view {@value #VIEW} of the P&amp;L inputs on a
 * date this server holds open on the mapped tier, the answer is the hot file's own float32 matrices, read in place (no copy): a map with
 * {@code positions} (a {@link FloatVectors} of one float32 row per position), {@code trades} (the float32 rows of the trades of positions
 * of several trades, or null), {@code positionIds} and {@code tradeIds} (the book's order, which the engine checks against its own before
 * it takes the rows), {@code scenarioSet} and {@code scenarioDates} (the rows' columns), {@code positionChunks} (the mapping's pieces). Every read goes through the mapped matrix's own
 * reader count, so a view read after the date is closed is refused ("unloaded, ask again"), never a crash. Null otherwise (the heap tier,
 * a date not open, a sharded or intraday-changed date): the engine builds its rows itself, as before.
 */
@Component
public class VarVectorViews implements VectorViews {

    /** The view's name (the engine's {@code InputTable.vectorView}). */
    public static final String VIEW = "float32-rows";
    static final String SETTING = "drishti.module.banking.var.zero-copy";
    private static final Logger LOG = LoggerFactory.getLogger(VarVectorViews.class);

    private final ObjectProvider<VarService> service;
    /** The refusals said once each (date and reason), not on every ask. */
    private final java.util.Set<String> told = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Environment env;

    VarVectorViews(ObjectProvider<VarService> service, Environment env) {
        this.service = service;
        this.env = env;
    }

    boolean on() {
        String v = env.getProperty(SETTING, "off").trim();
        return v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true");
    }

    @Override
    public Object view(String kind, String name, LocalDate date) {
        VarService svc = service.getIfAvailable();
        if (!VIEW.equals(name) || date == null || svc == null || !on() || !svc.props().kinds().vector().equals(kind)
                && !svc.props().kinds().scenarioPv().equals(kind)) {
            return null;
        }
        VarDate d = svc.heldDate(date).orElse(null);
        VarBook b = d == null ? null : d.book();
        VarBook.Hot hot = b == null ? null : b.hot();
        if (hot == null || hot.range() != null || hot.positions() == null || hot.positions().closed()) {
            String why = d == null ? "the date is not open here" : hot == null ? "the date is not on the mapped tier" : "the date's hot file is "
                    + (hot.range() != null ? "a shard's" : "closed");
            if (told.add(date + why)) {
                LOG.info("the float32 rows of {} not served to the engine ({}): it builds its own", date, why);
            }
            return null;
        }
        String[] positionIds = new String[b.positionCount()];
        for (int k = 0; k < positionIds.length; k++) {
            positionIds[k] = b.positionId(k);
        }
        String[] tradeIds = new String[b.tradeCount()];
        for (int o = 0; o < tradeIds.length; o++) {
            tradeIds[o] = b.tradeId(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("positions", new Rows(hot.positions(), d.version()));
        out.put("trades", hot.trades() == null ? null : new Rows(hot.trades(), d.version()));
        out.put("positionIds", positionIds);
        out.put("tradeIds", tradeIds);
        out.put("positionChunks", hot.positions().chunkCount());
        out.put("scenarioSet", b.scenarioSet());
        out.put("scenarioDates", b.scenarioDates().stream().map(LocalDate::toString).toList());
        LOG.info("the float32 rows of {} ({} positions, {} trade rows) served to the engine in place (zero copy, {})", date, positionIds.length,
                hot.trades() == null ? 0 : hot.trades().rows(), hot.file());
        return out;
    }

    /** A mapped float32 matrix as the engine API's read-only view; every read inside the matrix's reader count. */
    static final class Rows implements FloatVectors {

        private final MappedMatrix m;
        private final Lease lease;

        Rows(MappedMatrix m, String version) {
            this.m = m;
            this.lease = new Lease() {
                @Override
                public String version() {
                    return version;
                }

                @Override
                public boolean open() {
                    return !m.closed();
                }
            };
        }

        @Override
        public int rows() {
            return m.rows();
        }

        @Override
        public int width() {
            return m.width();
        }

        @Override
        public Lease lease() {
            return lease;
        }

        /** A row's values (a copy: a slice of the mapping would outlive the reader count; the engine reads by {@link #addInto}). */
        @Override
        public FloatBuffer row(int row) {
            double[] d = new double[m.width()];
            m.read(row, d);
            float[] f = new float[d.length];
            for (int j = 0; j < d.length; j++) {
                f[j] = (float) d[j];
            }
            return FloatBuffer.wrap(f).asReadOnlyBuffer();
        }

        @Override
        public float value(int row, int j) {
            return (float) m.value(row, j);
        }

        /** The rows' float32 values added into float64 accumulators in row order, as the in-tree book sums them. */
        @Override
        public void addInto(double[] acc, RowSet rows) {
            rows.forEachRun((from, to) -> m.addRangeInto(acc, from, to));
        }
    }
}
