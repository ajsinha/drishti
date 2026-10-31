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
package com.ash.drishti.server.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The scenario matrix ({@code SMAT <id> [N]}, VAR_GUIDE.md section 3.9): one trade, position or VaR node, its scenario P&amp;L by
 * scenario (rows, labelled by their historical day) and the last N business dates (columns), with each date's VaR rank and ES tail, its
 * base PV and where its numbers came from. Read as the person: the scope is resolved and checked by the engine on the newest date (their
 * nodes, their positions), the records read with their roles and masks.
 *
 * <p>Each date's column comes from, in this order:
 * <ul>
 *   <li>{@code open}: the date is open in the engine's memory (its hot file mapped): the scope's vector as every VaR answer computes it;</li>
 *   <li>{@code lake}: a trade, or a position its source sends whole, on a date not open: its one record read from the lake by id
 *       ({@link VarMatrixLake}), checked as the intake checks it;</li>
 *   <li>{@code official}: a node on a date not open whose official end-of-day capture holds its vector (only for a person who sees the
 *       whole node, as the official history);</li>
 *   <li>{@code engine}: anything else: the date opened by the engine (from its hot file when one was built), one date at a time.</li>
 * </ul>
 * A date with no vector, a wrong length or a refused row is a gap with its DRS reason, never zeros. The confidence, the ES confidence, the
 * scenario window and the horizon are parameters (the pickers to come pass them); only the 1-day horizon is computed today.
 */
final class VarMatrix {

    /** N when none is asked. */
    static final int DEFAULT_DAYS = 20;

    /** What a person asks: the id (a trade, a position, a VaR scope), the newest date, N, and the parameters. */
    record Asked(String id, LocalDate asOf, Integer days, String ccy, String by, String scenario, Double confidence, Double esConfidence,
            String window, Integer horizon) {}

    /** The thing the matrix is of, resolved on the newest date. */
    record Target(String scope, String kind, String label, String entity, boolean supplied, String node, VarService.Scoped anchor) {}

    /** One date's column before its measures. */
    record Col(LocalDate date, String source, String sourceNote, String setId, List<LocalDate> days, double[] pnl, double basePv,
            String valueType, String fx, String batch, String version, String link, String gap, List<String> warnings) {

        static Col gap(LocalDate date, String source, String why) {
            return new Col(date, source, null, null, List.of(), null, Double.NaN, null, null, null, null, null, why, List.of());
        }
    }

    private final VarService svc;
    private final VarViews views;
    private final VarMatrixLake lake;
    private final int maxDays;

    VarMatrix(VarService svc, VarViews views, VarMatrixLake lake, int maxDays) {
        this.svc = svc;
        this.views = views;
        this.lake = lake;
        this.maxDays = Math.max(1, maxDays);
    }

    int maxDays() {
        return maxDays;
    }

    // ------------------------------------------------------------------------------------------------------------------ the answer

    Map<String, Object> matrix(Principal p, Asked a) {
        int asked = a.days() == null || a.days() <= 0 ? DEFAULT_DAYS : a.days();
        int n = Math.min(asked, maxDays);
        if (a.horizon() != null && a.horizon() != 1) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "horizon " + a.horizon() + ": the scenario matrix computes the 1-day horizon today");
        }
        VarCalc calc = calc(a);
        String ccy = VarCurrencies.normalise(a.ccy());
        Target t = resolve(p, a.id(), a.asOf(), ccy);
        boolean byTrade = "trade".equalsIgnoreCase(a.by() == null ? "" : a.by().trim());
        if (byTrade && (!"position".equals(t.kind()) || t.supplied())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "by trade is for a position whose trades are known: " + t.scope()
                    + ("position".equals(t.kind()) ? " is sent whole by its source system (its trades have no vectors of their own)" : " is a " + t.kind()));
        }
        List<LocalDate> dates = new ArrayList<>(svc.loader().datesUpTo(t.anchor().date().date(), n));
        Collections.reverse(dates);                                           // oldest first, as the columns read
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("scope", t.scope());
        out.put("kind", t.kind());
        out.put("label", t.label());
        if (t.entity() != null) {
            out.put("entity", t.entity());
        }
        out.put("supplied", t.supplied());
        out.put("currency", ccy);
        out.put("days", dates.size());
        out.put("asked", asked);
        out.put("maxDays", maxDays);
        if (asked > maxDays) {
            out.put("note", asked + " dates asked; at most " + maxDays + " (drishti.var.smat.max-days)");
        }
        out.put("confidence", calc.confidence());
        out.put("esConfidence", calc.esConfidence());
        out.put("horizon", 1);
        out.put("window", a.window() == null || a.window().isBlank() ? "all" : a.window());
        out.put("labels", labels(calc));
        out.put("by", byTrade ? "trade" : "scenario");
        if (byTrade) {
            return byTrade(p, t, dates, ccy, a.scenario(), calc, out);
        }
        List<Col> cols = columns(p, t, dates, ccy);
        List<Map<String, Object>> meta = new ArrayList<>();
        List<double[]> values = new ArrayList<>();
        List<List<LocalDate>> days = new ArrayList<>();
        int win = window(a.window());
        for (Col c : cols) {
            double[] v = c.pnl();
            List<LocalDate> ds = c.days();
            if (v != null && win > 0 && win < v.length) {
                v = java.util.Arrays.copyOfRange(v, v.length - win, v.length);
                ds = ds.subList(ds.size() - win, ds.size());
            }
            values.add(v);
            days.add(ds);
            meta.add(column(c, v, ds, calc));
        }
        out.put("dates", meta);
        out.put("rows", rows(dates, values, days));
        return out;
    }

    private static int window(String w) {
        if (w == null || w.isBlank() || w.trim().equalsIgnoreCase("all")) {
            return 0;
        }
        try {
            return Integer.parseInt(VarProperties.checkWindow(w, "window"));
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    private VarCalc calc(Asked a) {
        VarProperties pr = svc.props();
        try {
            double c = a.confidence() == null ? pr.confidence() : VarProperties.checkConfidence(a.confidence(), "confidence");
            double ec = a.esConfidence() == null ? pr.esConfidence() : VarProperties.checkConfidence(a.esConfidence(), "es-confidence");
            return new VarCalc(c, ec, svc.calc().rule(), svc.calc().window(), svc.calc().scaleToVar());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** The summary rows' labels, from the parameters (the pickers to come change them without a code change). */
    static Map<String, String> labels(VarCalc calc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("var", "VaR " + pct(calc.confidence()) + " 1-day");
        m.put("es", "ES " + pct(calc.esConfidence()) + " 1-day");
        m.put("basePv", "Base PV");
        return m;
    }

    private static String pct(double c) {
        double x = Math.round(c * 1000) / 10.0;
        return (x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x)) + "%";
    }

    // ------------------------------------------------------------------------------------------------------------- what it is of

    /**
     * The scope an id means, on the newest date: a scope as typed ({@code DESK:RATES}, {@code BANK}, {@code NODE:MGMT~DESK-RATES}); a
     * P&amp;L vector's or a scenario PV's id ({@code PNL-<id>-<set>}, {@code SPV-...}) its trade or position; else a position id, then a
     * trade id. The engine checks it as the person (FORBIDDEN when they do not see it position by position).
     */
    Target resolve(Principal p, String id, LocalDate asOf, String ccy) {
        String text = id == null ? "" : id.trim();
        if (text.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a trade, a position or a VaR scope: SMAT T-1001, SMAT POS-..., SMAT DESK:RATES");
        }
        List<String> tries = new ArrayList<>();
        if (text.contains(":") || text.equalsIgnoreCase("BANK")) {
            tries.add(text.equalsIgnoreCase("BANK") ? "BANK" : text);
        } else {
            String inner = recordOf(text);
            for (String x : inner == null ? List.of(text) : List.of(inner, text)) {
                tries.add("POS:" + x);
                tries.add("TRD:" + x);
            }
        }
        DrishtiException last = null;
        for (String scope : tries) {
            VarService.Scoped s;
            try {
                s = svc.scoped(p, scope, asOf, ccy);
            } catch (DrishtiException e) {
                if (e.errorCode() == ErrorCode.FORBIDDEN) {
                    throw e;
                }
                last = e;
                continue;
            }
            if (!s.res().unknown().isEmpty() || s.res().set().isEmpty() && s.res().node() == null) {
                continue;
            }
            VarBook b = s.date().book();
            String entity = null;
            boolean supplied = false;
            if (s.res().position() >= 0) {
                entity = b.positionId(s.res().position());
                com.ash.drishti.compute.vector.OrdinalSet m = b.positionMembers(s.res().position());
                supplied = m.size() == 1 && b.supplied(m.first());
            } else if (s.res().trade() >= 0) {
                entity = b.tradeId(s.res().trade());
            }
            return new Target(s.res().canonical(), s.kind(), s.label(), entity, supplied, s.res().node(), s);
        }
        if (last != null && tries.size() == 1) {
            throw last;
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no trade, position or VaR scope " + text + " on the newest date: SMAT takes a "
                + "trade id, a position id, a P&L vector's or scenario PV's id, or a VaR scope (DESK:RATES, BOOK:BOOK-RATES-1, BANK)");
    }

    /** The trade or position a P&amp;L vector's or scenario PV's id names ({@code PNL-T1-HS-509}: T1), or null for any other id. */
    static String recordOf(String id) {
        for (String prefix : List.of(VarLoader.PNL_ID, "SPV-", VarLoader.OLD_PNL_ID)) {
            if (id.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String rest = id.substring(prefix.length());
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+)-([A-Za-z]+-\\d+)$").matcher(rest);
                return m.matches() ? m.group(1) : rest;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------- the columns

    private List<Col> columns(Principal p, Target t, List<LocalDate> dates, String ccy) {
        java.util.Set<LocalDate> open = svc.openDates();
        boolean entity = "trade".equals(t.kind()) || "position".equals(t.kind()) && t.supplied();
        boolean node = t.node() != null && t.anchor().view().full();
        Col[] out = new Col[dates.size()];
        List<Integer> engine = new ArrayList<>();
        List<Future<Col>> reads = new ArrayList<>();
        List<Integer> readAt = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < dates.size(); i++) {
                LocalDate d = dates.get(i);
                if (open.contains(d) || d.equals(t.anchor().date().date())) {
                    engine.add(i);                                            // open: computed in memory, no read
                } else if (entity || node) {
                    reads.add(pool.submit(() -> entity ? fromLake(p, t, d, ccy) : fromOfficial(t, d, ccy)));
                    readAt.add(i);
                } else {
                    engine.add(i);
                }
            }
            for (int r = 0; r < reads.size(); r++) {
                Col c;
                try {
                    c = reads.get(r).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    c = Col.gap(dates.get(readAt.get(r)), entity ? "lake" : "official", reason(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the scenario matrix was interrupted");
                }
                if (c == null) {                                              // not held there: the engine opens the date
                    engine.add(readAt.get(r));
                } else {
                    out[readAt.get(r)] = c;
                }
            }
        }
        engine.sort(null);
        for (int i : engine) {                                                // one date at a time: a date opened is a date in memory
            LocalDate d = dates.get(i);
            out[i] = fromEngine(p, t, d, ccy, open.contains(d) || d.equals(t.anchor().date().date()) ? "open" : "engine");
        }
        return List.of(out);
    }

    private static String reason(Throwable e) {
        if (e instanceof DrishtiException de) {
            return de.errorCode().code() + " " + de.getMessage();
        }
        return "DRS-1003 " + e;
    }

    /** A trade's or supplied position's record from the lake; null when only the engine can convert it. */
    private Col fromLake(Principal p, Target t, LocalDate d, String ccy) {
        VarLoader.ScenarioSet set;
        try {
            set = svc.loader().scenarioSet(d);
        } catch (DrishtiException e) {
            return Col.gap(d, "lake", reason(e));
        }
        VarMatrixLake.Column c = lake.read(p, t.entity(), d, set, ccy);
        if (c.useEngine()) {
            return null;
        }
        String link = c.recordId() == null ? null : "/v/" + c.kind() + "/" + c.recordId();
        return new Col(d, "lake", "its record " + (c.recordId() == null ? "" : c.recordId() + " ") + "read from the lake by id", set.id(),
                set.window(), c.pnl(), c.basePv(), c.valueType(), c.fx(), c.batch(), c.generation() < 0 ? null : "gen " + c.generation(), link,
                c.gap(), List.of());
    }

    /** A node's official end-of-day capture of the date with its vector; null when the date has none (the engine opens it). */
    @SuppressWarnings("unchecked")
    private Col fromOfficial(Target t, LocalDate d, String ccy) {
        Map<String, Object> idx = svc.official().index(d, ccy).orElse(null);
        if (idx == null || !(idx.get("nodes") instanceof Map<?, ?> nodes) || !(nodes.get(t.node()) instanceof Map<?, ?> f)) {
            return null;
        }
        Object capId = f.get("captureId");
        Map<String, Object> cap = capId == null ? null : (Map<String, Object>) svc.official().docs()
                .value(VarOfficialStore.FOLDER + capId + ".json", Map.class).orElse(null);
        if (cap == null || !(cap.get("vector") instanceof List<?> vec)) {
            return null;
        }
        VarLoader.ScenarioSet set;
        try {
            set = svc.loader().scenarioSet(d);
        } catch (DrishtiException e) {
            return null;
        }
        if (!set.id().equals(String.valueOf(cap.get("scenarioSet"))) || vec.size() != set.window().size()) {
            return null;                                                      // another set or window since: the engine computes it
        }
        double[] v = new double[vec.size()];
        for (int j = 0; j < v.length; j++) {
            v[j] = vec.get(j) instanceof Number x ? x.doubleValue() : Double.NaN;
            if (Double.isNaN(v[j])) {
                return Col.gap(d, "official", "DRS-5206 the official capture " + capId + " has no value under the scenario " + set.window().get(j));
            }
        }
        String batch = cap.get("provenance") instanceof Map<?, ?> pv && pv.get("batchIds") instanceof List<?> l ? join(l) : null;
        return new Col(d, "official", "the node's official end-of-day capture " + capId, set.id(), set.window(), v, Double.NaN, null,
                VarDate.USD.equals(ccy) ? "USD (as captured)" : ccy + " (the official record in " + ccy + ")", batch,
                cap.get("dataVersion") == null ? null : String.valueOf(cap.get("dataVersion")), "/var?capture=" + capId, null, List.of());
    }

    /** The scope on a date as the engine computes it (the date opened when it is not). */
    private Col fromEngine(Principal p, Target t, LocalDate d, String ccy, String source) {
        VarService.Scoped s;
        try {
            s = d.equals(t.anchor().date().date()) && ccy.equals(t.anchor().date().currency()) ? t.anchor() : svc.scoped(p, t.scope(), d, ccy);
        } catch (DrishtiException e) {
            return Col.gap(d, source, reason(e));
        }
        if (!s.date().date().equals(d)) {                                    // a refused date is answered by an earlier one: a gap here
            String why = s.date().warnings().stream().filter(w -> w.contains("not loaded") || w.contains("DRS-")).findFirst()
                    .orElse(String.join("; ", s.date().warnings()));
            return Col.gap(d, source, "DRS-5203 " + why.replaceFirst("^DRS-\\d+ ", ""));
        }
        if (!s.res().unknown().isEmpty()) {
            return Col.gap(d, source, "DRS-1001 " + String.join(", ", s.res().unknown()) + " is not held on " + d);
        }
        if (s.set().isEmpty()) {
            return Col.gap(d, source, "DRS-1001 no trades of " + t.scope() + " you see on " + d);
        }
        VarBook b = s.date().book();
        double[] pvUsd = {0};
        int[] counts = {0, 0};                                                // with a PV, sent as scenario PVs
        com.ash.drishti.compute.var.Columns tc = b.trades();
        boolean hasPv = tc.has("PV");
        VarIntake.Summary in = s.date().intake();
        s.set().forEach(o -> {
            double x = hasPv ? tc.number("PV", o) : Double.NaN;
            if (!Double.isNaN(x)) {
                pvUsd[0] += x;
                counts[0]++;
            }
            if (in != null && in.fromScenarioPv(b.tradeId(o))) {
                counts[1]++;
            }
        });
        int fromPv = counts[1];
        double base = counts[0] == 0 ? Double.NaN : pvUsd[0];
        String fx = "USD (no conversion)";
        if (!VarDate.USD.equals(ccy)) {
            ScenarioFx.Rates r = svc.fx(svc.date(d)).rates(ccy).orElse(null);
            base = r == null || Double.isNaN(base) ? Double.NaN : base / r.base();
            fx = "USD to " + ccy + " at each scenario's rate" + (r == null ? "" : " (" + r.source() + ")");
        }
        String valueType = fromPv == 0 ? "PNL" : fromPv == s.set().size() ? "PV" : "mixed";
        String link = "/var?scope=" + java.net.URLEncoder.encode(t.scope(), java.nio.charset.StandardCharsets.UTF_8);
        String entity = t.entity();
        if (entity != null && ("trade".equals(t.kind()) || t.supplied())) {
            boolean pv = s.date().intake() != null && s.date().intake().fromScenarioPv(entity);
            String rid = (pv ? "SPV-" : s.date().intake() == null ? VarLoader.PNL_ID : s.date().intake().pnlIdPrefix()) + entity + "-" + b.scenarioSet();
            link = "/v/" + (pv ? svc.props().kinds().scenarioPv() : svc.props().kinds().vector()) + "/" + rid;
        }
        String note = "open".equals(source) ? "the date open in the engine's memory" + (b.hot() != null ? " (its hot file)" : "")
                : "the date opened by the engine" + (b.hot() != null ? " from its hot file" : " from the lake");
        return new Col(d, source, note, b.scenarioSet(), b.scenarioDates(), s.vector(), base, valueType, fx,
                s.date().intake() == null ? null : join(s.date().intake().batchIds()), s.date().version(), link, null, s.date().warnings());
    }

    private static String join(List<?> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            out.add(String.valueOf(o));
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------------------------------------------- measures and rows

    /** A column's header: its date, source, measures, tail (the scenarios ranked within ES's count, with their rank) and gap. */
    static Map<String, Object> column(Col c, double[] v, List<LocalDate> days, VarCalc calc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", c.date().toString());
        m.put("source", c.source());
        m.put("sourceNote", c.sourceNote());
        m.put("scenarioSet", c.setId());
        m.put("valueType", c.valueType());
        m.put("fx", c.fx());
        m.put("batch", c.batch());
        m.put("dataVersion", c.version());
        m.put("link", c.link());
        m.put("warnings", c.warnings());
        if (v == null) {
            m.put("gap", c.gap() == null ? "DRS-1001 no vector on " + c.date() : c.gap());
            m.put("code", m.get("gap").toString().split(" ", 2)[0]);
            m.put("scenarios", 0);
            return m;
        }
        VarCalc.Measures ms = calc.measures(v);
        m.put("gap", null);
        m.put("scenarios", v.length);
        m.put("from", days.get(0).toString());
        m.put("to", days.get(days.size() - 1).toString());
        m.put("var", round2(ms.var()));
        m.put("es", round2(ms.es()));
        m.put("k", ms.k());
        m.put("esCount", ms.esCount());
        m.put("varScenario", days.get(ms.varScenario()).toString());
        m.put("basePv", Double.isNaN(c.basePv()) ? null : round2(c.basePv()));
        List<Map<String, Object>> tail = new ArrayList<>();
        int deep = Math.max(ms.k(), ms.esCount());
        for (int r = 0; r < Math.min(deep, ms.order().length); r++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("scenario", days.get(ms.order()[r]).toString());
            e.put("rank", r + 1);
            e.put("pnl", round2(v[ms.order()[r]]));
            tail.add(e);
        }
        m.put("tail", tail);
        return m;
    }

    /**
     * The rows: every scenario day any column holds, oldest first; a cell is the day's P&amp;L on that date, or null where the date's window
     * does not hold the day or the date is a gap (the header says which). Every row names every date (null, never left out).
     */
    static List<Map<String, Object>> rows(List<LocalDate> dates, List<double[]> values, List<List<LocalDate>> days) {
        TreeSet<LocalDate> all = new TreeSet<>();
        List<Map<LocalDate, Integer>> at = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            Map<LocalDate, Integer> idx = new java.util.HashMap<>();
            if (values.get(i) != null) {
                List<LocalDate> ds = days.get(i);
                for (int j = 0; j < ds.size(); j++) {
                    idx.put(ds.get(j), j);
                }
                all.addAll(ds);
            }
            at.add(idx);
        }
        List<Map<String, Object>> rows = new ArrayList<>(all.size());
        for (LocalDate s : all) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scenario", s.toString());
            for (int i = 0; i < dates.size(); i++) {
                Integer j = at.get(i).get(s);
                r.put(dates.get(i).toString(), j == null ? null : round2(values.get(i)[j]));
            }
            rows.add(r);
        }
        return rows;
    }

    static Double round2(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : Math.round(v * 100) / 100.0;
    }

    // -------------------------------------------------------------------------------------------------------------- by trade

    /**
     * A position's trades under one scenario day (the VaR scenario of the newest date when none is asked) across the dates: rows are the
     * trades, a cell a trade's P&amp;L under that day on that date; each date computed by the engine (a trade's own vector is the engine's).
     */
    private Map<String, Object> byTrade(Principal p, Target t, List<LocalDate> dates, String ccy, String scenario, VarCalc calc,
            Map<String, Object> out) {
        LocalDate day;
        try {
            day = scenario == null || scenario.isBlank()
                    ? t.anchor().date().book().scenarioDates().get(calc.measures(t.anchor().vector()).varScenario())
                    : LocalDate.parse(scenario.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "scenario " + scenario + " is not a day (YYYY-MM-DD)");
        }
        out.put("scenario", day.toString());
        java.util.Set<LocalDate> open = svc.openDates();
        List<Map<String, Object>> meta = new ArrayList<>();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (LocalDate d : dates) {
            String source = open.contains(d) || d.equals(t.anchor().date().date()) ? "open" : "engine";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("source", source);
            meta.add(m);
            VarService.Scoped s;
            try {
                s = svc.scoped(p, t.scope(), d, ccy);
            } catch (DrishtiException e) {
                m.put("gap", reason(e));
                continue;
            }
            int j = s.date().date().equals(d) ? s.date().book().scenarioDates().indexOf(day) : -1;
            if (!s.date().date().equals(d)) {
                m.put("gap", "DRS-5203 " + String.join("; ", s.date().warnings()));
                continue;
            }
            if (j < 0) {
                m.put("gap", "DRS-5001 the scenario day " + day + " is not in this date's window (" + s.date().book().scenarioDates().get(0) + " … "
                        + s.date().book().scenarioDates().get(s.date().book().width() - 1) + ")");
                continue;
            }
            m.put("gap", null);
            m.put("total", round2(s.vector()[j]));
            m.put("rank", rankOf(calc.measures(s.vector()).order(), j));
            for (VarViews.Part pt : views.parts(s, "trade", p)) {
                Map<String, Object> r = rows.computeIfAbsent(pt.key(), k -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("trade", pt.key());
                    x.put("label", pt.label());
                    for (String[] a : new String[][] {{"counterparty", "COUNTERPARTY"}, {"book", "TT_BOOK"}, {"productType", "PRODUCT_TYPE"},
                            {"tradeCurrency", "TRADE_CURRENCY"}}) {             // the person's masks, by the field's name in the data
                        Object v = pt.attrs().get(a[0]);
                        boolean hide = svc.access().masked(p, a[1]) || svc.access().masked(p, a[0]);
                        x.put(a[0], v == null ? null : hide ? com.ash.drishti.api.DataNode.MASK : String.valueOf(v));
                    }
                    return x;
                });
                r.put(d.toString(), round2(pt.vector()[j]));
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows.values()) {
            for (LocalDate d : dates) {
                r.putIfAbsent(d.toString(), null);                          // a trade not in the position that day: null, never left out
            }
            list.add(r);
        }
        out.put("dates", meta);
        out.put("rows", list);
        return out;
    }

    private static int rankOf(int[] order, int j) {
        for (int r = 0; r < order.length; r++) {
            if (order[r] == j) {
                return r + 1;
            }
        }
        return -1;
    }
}
