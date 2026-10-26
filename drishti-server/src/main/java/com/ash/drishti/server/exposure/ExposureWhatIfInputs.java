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
package com.ash.drishti.server.exposure;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.CsaTerms;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Python what-if engine ({@code exposure-whatif}, docs/guides/EXPOSURE_GUIDE.md section 9) reads from the Java engine, as
 * the viewer, through the computed kind {@code exposure-result} with the parameter {@code detail}:
 *
 * <ul>
 *   <li>{@code detail=whatif}: the scope's exposure and negative exposure on every path at every primary date ({@code E_SCOPE},
 *       {@code N_SCOPE}, {@code G_primary x P}, row-major), the primary times, tenors and dates, the horizon, the current exposure, and
 *       {@code WHATIF_SETS}: the ids of the netting sets a candidate may join ({@code sets}: the ones named, else every netting set the
 *       scope covers whole, at most {@link #MAX_SETS}), as {@code exposure-result} ids the engine's second read follows;</li>
 *   <li>{@code detail=set} on a netting set: its value on every path at every grid date ({@code V}, {@code G x P}), its exposure under
 *       the asked collateral model ({@code E_SET}, {@code N_SET}), the grid's roles and look-back points, its CSA terms in USD, what it
 *       holds today, its MtM today and the longest remaining maturity of its trades.</li>
 * </ul>
 * Vectors are flat lists of numbers (one Arrow fixed-size vector per row); everything the Python engine needs to recompute a set's
 * collateral and exposure exactly as {@link ExposureMath} does.
 */
final class ExposureWhatIfInputs {

    /** The most netting sets one what-if reads the paths of (a candidate joins one of them, or is a netting set of its own). */
    static final int MAX_SETS = 50;

    private ExposureWhatIfInputs() {}

    /** The scope's fields for a what-if ({@code detail=whatif}); {@code sets} the netting sets the candidates join (blank: all, whole). */
    static void base(ExposureService.Scoped s, String sets, String rule, Map<String, Object> row) {
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        row.put("E_SCOPE", list(s.result().e));
        row.put("N_SCOPE", list(s.result().n));
        row.put("TIMES", list(ExposureCalc.times(g)));
        grid(g, row);
        List<String> tenors = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (int i = 0; i < g.primaryCount(); i++) {
            tenors.add(g.tenor(g.primaryAt(i)));
            dates.add(g.date(g.primaryAt(i)).toString());
        }
        row.put("TENORS", String.join(",", tenors));
        row.put("DATES", String.join(",", dates));
        row.put("RULE", rule);
        row.put("CE_SCOPE", s.result().stats.ce);
        row.put("HORIZON", s.result().stats.horizon);
        row.put("LIFE_YEARS", life(b, s.result()));
        Set<String> want = new LinkedHashSet<>();
        for (String x : (sets == null ? "" : sets).split(",")) {
            String id = x.trim().toUpperCase(Locale.ROOT);
            if (!id.isEmpty() && !id.equals("*")) {
                want.add(id.startsWith("NS-") ? id : "NS-" + id);
            }
        }
        List<String> refs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ExposureCalc.Piece pc : s.result().pieces) {
            String id = b.set(pc.set).id();
            if (!pc.whole || !want.isEmpty() && !want.contains(id)) {
                continue;
            }
            if (refs.size() >= MAX_SETS) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BATCH_JOB, "a what-if reads the paths of at most " + MAX_SETS + " netting sets: name the "
                        + "netting sets the candidates join (sets=...), or ask at the counterparty or the netting set");
            }
            refs.add("NSET:" + id + "~collateral=" + s.ask().collateral() + "~detail=set~q=" + s.ask().q());
            ids.add(id);
        }
        for (String w : want) {
            if (!ids.contains(w)) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the netting set " + w + " is not one the scope " + s.res().canonical()
                        + " covers whole: a candidate joins a netting set of the scope (or none: a netting set of its own)");
            }
        }
        row.put("WHATIF_SETS", refs);
        row.put("WHATIF_SET_IDS", String.join(",", ids));
    }

    /** A netting set's fields for a what-if ({@code detail=set}). */
    static void set(ExposureService.Scoped s, Map<String, Object> row) {
        if (!s.kind().equals("netting-set") || s.result().pieces.size() != 1 || !s.result().pieces.get(0).whole) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "detail=set is for a netting set seen whole, not " + s.res().canonical());
        }
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        ExposureCalc.Piece pc = s.result().pieces.get(0);
        ExposureBook.SetInfo si = b.set(pc.set);
        CsaTerms c = si.csa();
        double[] v = b.value(pc.set);
        row.put("NETTING_SET", si.id());
        row.put("V", list(v));
        row.put("E_SET", list(s.result().e));
        row.put("N_SET", list(s.result().n));
        grid(g, row);
        row.put("CSA_PRESENT", c.present());
        row.put("MARGINED", c.margined());
        row.put("TH_THEM", c.thThem());
        row.put("TH_US", c.thUs());
        row.put("MTA_THEM", c.mtaThem());
        row.put("MTA_US", c.mtaUs());
        row.put("IA", c.ia());
        row.put("ROUNDING", c.rounding());
        row.put("HELD_TODAY", c.heldToday());
        row.put("IM_HELD", c.imHeld());
        row.put("V0", si.v0());
        row.put("CE_SET", pc.ce);
        row.put("LIFE_YEARS", si.lifeYears());
    }

    /** The grid as vectors of G: {@code PRIMARY} (1 or 0), {@code LOOKBACK_FOR} (a primary date's look-back point, -1: none), {@code TIMES_ALL}. */
    private static void grid(ExposureGrid g, Map<String, Object> row) {
        double[] primary = new double[g.size()];
        double[] lookback = new double[g.size()];
        double[] times = new double[g.size()];
        for (int k = 0; k < g.size(); k++) {
            primary[k] = g.primary(k) ? 1 : 0;
            lookback[k] = g.primary(k) ? g.lookbackFor(k) : -1;
            times[k] = g.time(k);
        }
        row.put("PRIMARY", list(primary));
        row.put("LOOKBACK_FOR", list(lookback));
        row.put("TIMES_ALL", list(times));
    }

    private static double life(ExposureBook b, ExposureCalc.Result r) {
        double life = 0;
        for (ExposureCalc.Piece pc : r.pieces) {
            for (int t : pc.trades) {
                life = Math.max(life, b.trade(t).lifeYears());
            }
        }
        return life;
    }

    private static List<Double> list(double[] a) {
        List<Double> out = new ArrayList<>(a.length);
        for (double x : a) {
            out.add(x);
        }
        return out;
    }
}
