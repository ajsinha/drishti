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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.compute.ComputeRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.reactive.Answer;
import com.ash.drishti.compute.reactive.ReactiveConnector;
import com.ash.drishti.compute.reactive.ReactiveHub;
import com.ash.drishti.compute.reactive.Sandboxes;
import com.ash.drishti.server.compute.Viewers;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The exact what-if (EXP.md section 6.7, method {@code paths}): each candidate's MtM on every path of the date's run at every grid date
 * is added to its netting set's value, the set's collateral and exposure computed again, then the scope's figures; incremental = with
 * - without, per figure and per primary date. Asked here, answered by the Python exposure engine: the candidates go into a private
 * sandbox of the asker, the reactive connector {@code exposure-whatif} is asked as them (it reads the scope's and the touched sets'
 * matrices from {@code exposure-result} with {@code detail}, ExposureWhatIfInputs), the sandbox is deleted after.
 *
 * <p>A candidate's paths: {@code {copyOf: <trade>, factor}} (a booked trade's paths on the run, scaled: {@code factor -1} its
 * unwind), {@code {runId, mtm}} ({@code G} lists of {@code P} numbers, or one list of {@code G x P}, grid-date-major), or
 * {@code {runId, arrow}} (base64 Arrow IPC: {@code GRID_INDEX} and {@code MTM} as a fixed-size list of P). Paths of another run, or of
 * another length, are refused (DRS-5442); without the Python engine the answer is DRS-5446 (503).
 */
final class ExposureWhatIfPaths {

    static final String CONNECTOR = "exposure-whatif";
    static final String KIND = "exposure-whatif";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private final ExposureService svc;

    ExposureWhatIfPaths(ExposureService svc) {
        this.svc = svc;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> whatIf(Principal p, String scope, ExposureService.Scoped base, ExposureService.Ask ask, List<Map<String, Object>> cands,
            LocalDate date) {
        ReactiveHub hub = ReactiveHub.get();
        ReactiveConnector c = hub.connector(CONNECTOR).orElseThrow(() -> new DrishtiException(ErrorCode.EXPOSURE_METHOD_UNAVAILABLE,
                "the exact what-if (method paths) runs in the Python exposure engine, the reactive connector " + CONNECTOR + " of the "
                        + "counterparty-risk pack, which is not running on this server (its compute pool exposure); ask method addon for the "
                        + "conservative proxy"));
        ExposureDate d = base.date();
        ExposureBook b = d.book();
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> sets = new LinkedHashSet<>();
        for (Map<String, Object> cand : cands) {
            Map<String, Object> it = item(cand, d, items.size() + 1);
            items.add(it);
            if (!String.valueOf(it.get("NETTING_SET")).isEmpty()) {
                sets.add(String.valueOf(it.get("NETTING_SET")));
            }
        }
        Sandboxes.Sandbox box;
        try {
            box = hub.sandboxes().create(p.user(), CONNECTOR, "exposure what-if on " + scope, items);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sandbox", box.id());
            params.put("q", ask.q());
            params.put("collateral", ask.collateral());
            params.put("sets", String.join(",", sets));
            String ref = ComputeRef.format(base.res().canonical(), params);
            Answer a = c.answer(new ReactiveConnector.Ask(KIND, ref, AsOf.of(d.date()), Viewers.of(p), RequestClass.INTERACTIVE, WAIT));
            if (!a.ready() || a.document() == null) {
                throw new DrishtiException(a.state().equals(Answer.REFUSED) || a.state().equals(Answer.FAILED) ? ErrorCode.EXPOSURE_BAD_PARAMETER
                        : ErrorCode.EXPOSURE_METHOD_UNAVAILABLE, "the Python exposure engine " + (a.state().equals(Answer.COMPUTING)
                        ? "did not answer within " + WAIT.toSeconds() + " s" : "answered " + a.state()) + (a.message() == null ? "" : ": " + a.message()));
            }
            Map<String, Object> out = answer(a.document(), base, scope, b, items.size());
            svc.audit(p, "ccr-whatif", base, "method=paths candidates=" + items.size() + " sets=" + sets);
            return out;
        } finally {
            hub.sandboxes().delete(p.user(), box.id());
        }
    }

    /** One candidate as a sandbox item: its id, label, netting set (empty: a netting set of its own), paths, MtM today, life. */
    private Map<String, Object> item(Map<String, Object> cand, ExposureDate d, int n) {
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
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("TRADE_ID", id);
        it.put("LABEL", cand.get("label") == null ? id : String.valueOf(cand.get("label")));
        it.put("NETTING_SET", set);
        List<Double> v = new ArrayList<>(m.length);
        for (double x : m) {
            v.add(x);
        }
        it.put("MTM", v);
        it.put("MTM0", mtm0 instanceof Number x ? x.doubleValue() : null);
        it.put("LIFE_YEARS", life);
        it.put("RUN_ID", b.runId());
        return it;
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

    /** The Python engine's answer in the API's shape (the addon method's: base, with, incremental with the profile). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> answer(Map<String, Object> doc, ExposureService.Scoped base, String scope, ExposureBook b, int n) {
        Map<String, Object> fBase = new LinkedHashMap<>();
        Map<String, Object> fWith = new LinkedHashMap<>();
        Map<String, Object> inc = new LinkedHashMap<>();
        for (String[] f : new String[][] {{"epe", "EPE"}, {"eepe", "EEPE"}, {"peakPfe", "PEAK_PFE"}, {"peakEe", "PEAK_EE"}, {"ce", "CE"}}) {
            fBase.put(f[0], round(doc.get("BEFORE_" + f[1])));
            fWith.put(f[0], round(doc.get("AFTER_" + f[1])));
            inc.put(f[0], round(doc.get("CHANGE_" + f[1])));
        }
        fBase.put("peakPfeTenor", doc.get("BEFORE_PEAK_PFE_TENOR"));
        fWith.put("peakPfeTenor", doc.get("AFTER_PEAK_PFE_TENOR"));
        Map<String, Object> tables = doc.get("TABLES") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Map<String, Object>> profile = new ArrayList<>();
        if (tables.get("profile") instanceof List<?> rows) {
            for (Object o : rows) {
                Map<String, Object> r = (Map<String, Object>) o;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("tenor", r.get("TENOR"));
                x.put("date", r.get("DATE"));
                x.put("t", r.get("T"));
                x.put("eeBase", round(r.get("EE_BASE")));
                x.put("ee", round(r.get("EE")));
                x.put("pfeBase", round(r.get("PFE_BASE")));
                x.put("pfe", round(r.get("PFE")));
                x.put("incrementalEe", round(r.get("INCREMENTAL_EE")));
                x.put("incrementalPfe", round(r.get("INCREMENTAL_PFE")));
                profile.add(x);
            }
        }
        inc.put("profile", profile);
        List<Map<String, Object>> items = new ArrayList<>();
        if (tables.get("items") instanceof List<?> rows) {
            for (Object o : rows) {
                Map<String, Object> r = (Map<String, Object>) o;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("tradeId", r.get("TRADE_ID"));
                x.put("label", r.get("LABEL"));
                x.put("nettingSet", r.get("NETTING_SET"));
                x.put("peakPfeAlone", round(r.get("PEAK_PFE_ALONE")));
                x.put("incrementalPeakPfe", round(r.get("INCREMENTAL_PEAK_PFE")));
                items.add(x);
            }
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
        out.put("candidates", n);
        out.put("items", items);
        out.put("flags", doc.get("FLAGS") instanceof List<?> l ? l : List.of());
        out.put("note", "exact: each candidate's paths on the run " + b.runId() + " added to its netting set path by path, collateral and exposure "
                + "computed again by the Python exposure engine (exposure-whatif)");
        out.put("engine", doc.get("ENGINE"));
        return out;
    }

    private static Long round(Object o) {
        return o instanceof Number x && Double.isFinite(x.doubleValue()) ? Math.round(x.doubleValue()) : null;
    }
}
