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
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.compute.ccr.ExposureWhatIfMath;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The exact what-if (EXPOSURE_GUIDE.md section 18.7, method {@code paths}), computed here in the Java exposure engine by
 * {@link ExposureWhatIfMath}: each candidate's MtM on every path of the date's run at every grid date is added to its netting set's value,
 * the set's collateral and exposure computed again, then the scope's figures; incremental = with - without, per figure and per primary
 * date. The scope and each netting set a candidate joins are read as the asker sees them (their entitlements apply); a candidate joins a
 * netting set the scope covers whole, or none (a netting set of its own, uncollateralised).
 *
 * <p>A candidate's paths: {@code {copyOf: <trade>, factor}} (a booked trade's paths on the run, scaled: {@code factor -1} its
 * unwind), {@code {runId, mtm}} ({@code G} lists of {@code P} numbers, or one list of {@code G x P}, grid-date-major), or
 * {@code {runId, arrow}} (base64 Arrow IPC: {@code GRID_INDEX} and {@code MTM} as a fixed-size list of P). Paths of another run, or of
 * another length, are refused (DRS-5442). A copy is made only of a trade the asker sees trade by trade (else 403).
 */
final class ExposureWhatIfPaths {

    /** The most netting sets one what-if's candidates join (each read whole, its value on every path). */
    static final int MAX_SETS = 50;
    private final ExposureService svc;

    ExposureWhatIfPaths(ExposureService svc) {
        this.svc = svc;
    }

    Map<String, Object> whatIf(Principal p, String scope, ExposureService.Scoped base, ExposureService.Ask ask, List<Map<String, Object>> cands,
            LocalDate date, boolean audited) {
        ExposureDate d = base.date();
        ExposureBook b = d.book();
        ExposureGrid g = b.grid();
        List<ExposureWhatIfMath.Candidate> items = new ArrayList<>();
        Map<String, ExposureWhatIfMath.NettingSet> sets = new LinkedHashMap<>();
        for (Map<String, Object> cand : cands) {
            ExposureWhatIfMath.Candidate it = item(cand, d, base.view(), items.size() + 1);
            items.add(it);
            if (!it.set().isEmpty() && !sets.containsKey(it.set())) {
                if (sets.size() >= MAX_SETS) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BATCH_JOB, "a what-if's candidates join at most " + MAX_SETS + " netting sets");
                }
                sets.put(it.set(), set(p, base, it.set(), date, ask));
            }
        }
        ExposureMath.Model model = ExposureMath.Model.of(ask.collateral());
        ExposureCalc.Result r = base.result();
        ExposureWhatIfMath.Result w;
        try {
            w = ExposureWhatIfMath.of(new ExposureWhatIfMath.Scope(r.e, r.n, r.stats.ce, life(b, r)), sets, items, g, b.paths(), ExposureCalc.times(g),
                    ask.q(), model, svc.props().rule(), base.res().canonical());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
        }
        Map<String, Object> out = answer(w, base, scope, b, model);
        if (audited) {
            svc.audit(p, "ccr-whatif", base, "method=paths candidates=" + items.size() + " sets=" + sets.keySet());
        }
        return out;
    }

    /** A netting set a candidate joins: one the scope covers whole, read as the asker sees it. */
    private ExposureWhatIfMath.NettingSet set(Principal p, ExposureService.Scoped base, String id, LocalDate date, ExposureService.Ask ask) {
        ExposureBook b = base.date().book();
        boolean whole = base.result().pieces.stream().anyMatch(pc -> pc.whole && b.set(pc.set).id().equals(id));
        if (!whole) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the netting set " + id + " is not one the scope " + base.res().canonical()
                    + " covers whole: a candidate joins a netting set of the scope (or none: a netting set of its own)");
        }
        ExposureService.Scoped s = svc.scoped(p, "NSET:" + id, date, ask);
        if (s.result().pieces.size() != 1 || !s.result().pieces.get(0).whole) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the netting set " + id + " is not seen whole: a candidate joins a netting "
                    + "set you see whole (or none: a netting set of its own)");
        }
        ExposureCalc.Piece pc = s.result().pieces.get(0);
        ExposureBook.SetInfo si = b.set(pc.set);
        return new ExposureWhatIfMath.NettingSet(si.id(), b.value(pc.set), s.result().e, s.result().n, si.csa(), si.v0(), pc.ce);
    }

    /** The longest remaining life of the scope's trades, in years. */
    private static double life(ExposureBook b, ExposureCalc.Result r) {
        double life = 0;
        for (ExposureCalc.Piece pc : r.pieces) {
            for (int t : pc.trades) {
                life = Math.max(life, b.trade(t).lifeYears());
            }
        }
        return life;
    }

    /** One candidate: its id, label, netting set (empty: a netting set of its own), paths, MtM today (NaN: the paths' mean), life. */
    private ExposureWhatIfMath.Candidate item(Map<String, Object> cand, ExposureDate d, ExposureAccess.View view, int n) {
        ExposureBook b = d.book();
        int len = b.grid().size() * b.paths();
        String id = cand.get("tradeId") == null ? "CANDIDATE-" + n : String.valueOf(cand.get("tradeId"));
        if (!(cand.get("paths") instanceof Map<?, ?> pm)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "method paths needs each candidate's paths on the run " + b.runId()
                    + ": paths: {copyOf: <trade>, factor} or {runId, mtm: [" + b.grid().size() + " lists of " + b.paths() + "]}; " + id + " has none");
        }
        double[] m;
        double life;
        Object mtm0 = cand.get("mtm");
        if (pm.get("copyOf") != null) {
            String from = String.valueOf(pm.get("copyOf")).trim();
            int t = b.tradeOrdinal(from);
            if (t < 0) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths for the trade " + from + " on " + d.date() + " (run " + b.runId() + ")");
            }
            if (!view.seesTrades(com.ash.drishti.compute.vector.OrdinalSet.of(t))) {       // a copy shows the trade's paths: only for who sees it
                throw new DrishtiException(ErrorCode.FORBIDDEN, "the trade " + from + " is not one you see trade by trade: a copy is made of a trade "
                        + "you see");
            }
            double f = pm.get("factor") instanceof Number x ? x.doubleValue() : 1.0;
            double[] src = svc.paths(d).read(List.of(b.trade(t).id())).get(b.trade(t).id());
            if (src == null) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths for the trade " + from + " on " + d.date());
            }
            m = new double[src.length];
            for (int i = 0; i < m.length; i++) {
                m[i] = f * src[i];
            }
            life = b.trade(t).lifeYears();
            if (mtm0 == null) {
                mtm0 = f * b.trade(t).mtm0();
            }
        } else {
            if (pm.get("runId") != null && !b.runId().equals(String.valueOf(pm.get("runId")))) {
                throw new DrishtiException(ErrorCode.EXPOSURE_WHATIF_RUN, "the candidate's paths are not of the run " + b.runId() + " of " + d.date()
                        + ": they are of " + pm.get("runId"));
            }
            m = pm.get("arrow") != null ? arrow(String.valueOf(pm.get("arrow")), b) : numbers(pm.get("mtm"));
            life = years(d.date(), cand.get("maturityDate"));
        }
        if (m.length != len) {
            throw new DrishtiException(ErrorCode.EXPOSURE_WHATIF_RUN, "the candidate " + id + "'s paths are not of the run " + b.runId() + ": "
                    + m.length + " values, the run has " + b.grid().size() + " grid dates x " + b.paths() + " paths = " + len);
        }
        String set = cand.get("nettingSet") == null ? "" : String.valueOf(cand.get("nettingSet")).trim().toUpperCase(Locale.ROOT);
        if (!set.isEmpty() && !set.startsWith("NS-")) {
            set = "NS-" + set;
        }
        String label = cand.get("label") == null ? id : String.valueOf(cand.get("label"));
        double today = mtm0 instanceof Number x && Double.isFinite(x.doubleValue()) ? x.doubleValue() : Double.NaN;
        return new ExposureWhatIfMath.Candidate(id, label, set, m, today, life);
    }

    private static double[] numbers(Object o) {
        if (!(o instanceof List<?> l)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's paths.mtm is a list of numbers (grid date by grid date)");
        }
        List<Double> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof List<?> row) {
                row.forEach(y -> out.add(num(y)));
            } else {
                out.add(num(x));
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double num(Object y) {
        if (y instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's path value is a finite number, not " + y);
    }

    /** Base64 Arrow IPC with {@code GRID_INDEX} and {@code MTM} (a fixed-size list of P) into {@code G x P}, an absent date zero. */
    private static double[] arrow(String b64, ExposureBook b) {
        try {
            ArrowTable t = ArrowIpc.read(Base64.getDecoder().decode(b64.trim()));
            double[] out = new double[b.grid().size() * b.paths()];
            long[] k = t.longs("GRID_INDEX");
            for (int r = 0; r < t.rows(); r++) {
                double[] row = t.vector("MTM", r);
                if (k[r] < 0 || k[r] >= b.grid().size() || row.length != b.paths()) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_WHATIF_RUN, "the candidate's Arrow paths are not of the run " + b.runId() + ": grid index "
                            + k[r] + " with " + row.length + " paths (the run: " + b.grid().size() + " dates, " + b.paths() + " paths)");
                }
                System.arraycopy(row, 0, out, (int) k[r] * b.paths(), b.paths());
            }
            return out;
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's paths.arrow is base64 Arrow IPC with GRID_INDEX and MTM (a "
                    + "fixed-size list of float64): " + e.getMessage());
        }
    }

    private static double years(LocalDate bd, Object d) {
        if (d == null) {
            return 1;
        }
        try {
            return Math.max(1 / 365.0, ChronoUnit.DAYS.between(bd, LocalDate.parse(String.valueOf(d).substring(0, 10))) / 365.0);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's maturityDate is a date (YYYY-MM-DD), not " + d);
        }
    }

    /** The answer in the API's shape (the addon method's: base, with, incremental with the profile; and each candidate's figures). */
    private static Map<String, Object> answer(ExposureWhatIfMath.Result w, ExposureService.Scoped base, String scope, ExposureBook b,
            ExposureMath.Model model) {
        ExposureGrid g = b.grid();
        ExposureMath.Stats s0 = w.before();
        ExposureMath.Stats s1 = w.after();
        Map<String, Object> fBase = new LinkedHashMap<>();
        Map<String, Object> fWith = new LinkedHashMap<>();
        Map<String, Object> inc = new LinkedHashMap<>();
        double[][] figures = {{s0.epe, s1.epe}, {s0.eepe, s1.eepe}, {s0.peakPfe[0], s1.peakPfe[0]}, {s0.peakEe, s1.peakEe}, {s0.ce, s1.ce}};
        String[] names = {"epe", "eepe", "peakPfe", "peakEe", "ce"};
        for (int f = 0; f < names.length; f++) {
            fBase.put(names[f], Math.round(figures[f][0]));
            fWith.put(names[f], Math.round(figures[f][1]));
            inc.put(names[f], Math.round(figures[f][1] - figures[f][0]));
        }
        fBase.put("peakPfeTenor", tenor(g, s0.peakAt[0]));
        fWith.put("peakPfeTenor", tenor(g, s1.peakAt[0]));
        double[] times = ExposureCalc.times(g);
        List<Map<String, Object>> profile = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("tenor", g.tenor(g.primaryAt(i)));
            x.put("date", g.date(g.primaryAt(i)).toString());
            x.put("t", times[i]);
            x.put("eeBase", Math.round(s0.ee[i]));
            x.put("ee", Math.round(s1.ee[i]));
            x.put("pfeBase", Math.round(s0.pfe[0][i]));
            x.put("pfe", Math.round(s1.pfe[0][i]));
            x.put("incrementalEe", Math.round(s1.ee[i] - s0.ee[i]));
            x.put("incrementalPfe", Math.round(s1.pfe[0][i] - s0.pfe[0][i]));
            profile.add(x);
        }
        inc.put("profile", profile);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExposureWhatIfMath.Item it : w.items()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("tradeId", it.id());
            x.put("label", it.label());
            x.put("nettingSet", it.set());
            x.put("peakPfeAlone", Math.round(it.peakPfeAlone()));
            x.put("incrementalPeakPfe", Math.round(it.incrementalPeakPfe()));
            items.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", base.res().canonical());
        out.put("businessDate", b.businessDate().toString());
        out.put("runId", b.runId());
        out.put("confidence", base.ask().q());
        out.put("method", "paths");
        out.put("base", fBase);
        out.put("with", fWith);
        out.put("incremental", inc);
        out.put("candidates", w.items().size());
        out.put("items", items);
        out.put("flags", List.of("exact"));
        out.put("note", "exact: each candidate's paths on the run " + b.runId() + " added to its netting set path by path, collateral (" + model.text()
                + ") and exposure computed again by the Java exposure engine");
        out.put("engine", "exposure (Java)");
        return out;
    }

    private static String tenor(ExposureGrid g, int i) {
        return i >= 0 && i < g.primaryCount() ? g.tenor(g.primaryAt(i)) : null;
    }
}
