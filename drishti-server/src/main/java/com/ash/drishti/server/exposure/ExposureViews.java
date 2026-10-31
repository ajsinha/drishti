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
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The exposure engine's answers as the API and the screens return them (EXPOSURE_GUIDE.md section 19): a scope's figures and profile with its
 * provenance, the profile alone (for charts, also as CSV), the exposure distribution at a grid date, the contributions of its parts,
 * and the tree of groups, counterparties and netting sets. Amounts are whole USD; times in years.
 */
final class ExposureViews {

    /** The engine's version (the drishti-compute module's): stamped on answers and on the computed kind. */
    static final String ENGINE_VERSION = versionOf();
    private static final List<Double> QUANTILES = List.of(0.5, 0.9, 0.95, 0.975, 0.99);
    private final ExposureService svc;
    private final ExposureExplorer explorer;

    ExposureViews(ExposureService svc) {
        this.svc = svc;
        this.explorer = new ExposureExplorer(svc);
    }

    ExposureService service() {
        return svc;
    }

    private static String versionOf() {
        String v = ExposureBook.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    // ---------------------------------------------------------------------------------------------------------------------- the scope

    Map<String, Object> scope(Principal p, String scope, LocalDate date, ExposureService.Ask ask) {
        ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
        ExposureBook b = s.date().book();
        ExposureMath.Stats st = s.result().stats;
        int qa = st.at(ask.q());
        int q99 = st.at(0.99);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", s.res().canonical());
        out.put("kind", s.kind());
        out.put("label", s.label());
        out.put("businessDate", s.date().date().toString());
        out.put("runId", b.runId());
        out.put("paths", b.paths());
        out.put("calibration", b.run().get("CALIBRATION"));
        out.put("confidence", ask.q());
        out.put("ce", Math.round(st.ce));
        out.put("epe", Math.round(st.epe));
        out.put("eepe", Math.round(st.eepe));
        out.put("peakEe", Math.round(st.peakEe));
        out.put("peakPfe", Math.round(st.peakPfe[qa]));
        int at = st.peakAt[qa];
        out.put("peakPfeDate", at < 0 ? null : b.grid().date(b.grid().primaryAt(at)).toString());
        out.put("peakPfeTenor", at < 0 ? null : b.grid().tenor(b.grid().primaryAt(at)));
        out.put("peakPfe99", q99 < 0 ? null : Math.round(st.peakPfe[q99]));
        out.put("eadImm", Math.round(svc.props().alpha() * st.eepe));
        out.put("alpha", svc.props().alpha());
        out.put("horizonYears", round4(st.horizon));
        out.put("mporDays", b.run().get("LOOKBACK_DAYS"));
        out.put("collateralModel", ask.collateral());
        out.put("profile", profileRows(s, false));
        out.put("byNettingSet", byNettingSet(p, s));
        out.put("flags", s.result().flags);
        out.put("provenance", provenance(s));
        explorer.augment(p, s, out);
        svc.audit(p, "ccr-view", s, null);
        return out;
    }

    /** The profile at the primary dates ({@code all}: every grid date, the look-back points with their uncollateralised EE only). */
    List<Map<String, Object>> profileRows(ExposureService.Scoped s, boolean all) {
        ExposureBook b = s.date().book();
        ExposureGrid g = b.grid();
        ExposureMath.Stats st = s.result().stats;
        int qa = st.at(s.ask().q());
        int q95 = st.at(0.95);
        int q99 = st.at(0.99);
        List<Map<String, Object>> rows = new ArrayList<>();
        double[] lookUncoll = all ? lookbackUncoll(s) : null;
        for (int k = 0; k < g.size(); k++) {
            int i = g.primaryIndex(k);
            if (i < 0 && !all) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tenor", i < 0 ? null : g.tenor(k));
            r.put("date", g.date(k).toString());
            r.put("t", round4(g.time(k)));
            r.put("role", i < 0 ? "LOOKBACK" : "PRIMARY");
            if (i >= 0) {
                r.put("ee", Math.round(st.ee[i]));
                r.put("ene", Math.round(st.ene[i]));
                r.put("eee", Math.round(st.eee[i]));
                r.put("pfe", Math.round(st.pfe[qa][i]));
                r.put("pfe95", q95 < 0 ? null : Math.round(st.pfe[q95][i]));
                r.put("pfe99", q99 < 0 ? null : Math.round(st.pfe[q99][i]));
                r.put("eeUncoll", Math.round(st.eeUncoll[i]));
            } else {
                r.put("eeUncoll", Math.round(lookUncoll[k]));
            }
            rows.add(r);
        }
        return rows;
    }

    /** max(V, 0) means at every grid date (the look-back points' uncollateralised EE), from the scope's sets. */
    private double[] lookbackUncoll(ExposureService.Scoped s) {
        ExposureBook b = s.date().book();
        int p = b.paths();
        int g = b.grid().size();
        double[] out = new double[g];
        Map<String, double[]> tp = null;
        for (ExposureCalc.Piece pc : s.result().pieces) {
            double[] v;
            if (pc.whole) {
                v = b.value(pc.set);
            } else {
                if (tp == null) {
                    List<String> ids = new ArrayList<>();
                    s.result().trades.forEach(t -> ids.add(b.trade(t).id()));
                    tp = svc.paths(s.date()).read(ids);
                }
                v = new double[g * p];
                for (int t : pc.trades) {
                    double[] m = tp.get(b.trade(t).id());
                    if (m != null) {
                        ExposureMath.addInto(v, m);
                    }
                }
            }
            for (int k = 0; k < g; k++) {
                double sum = 0;
                for (int j = 0; j < p; j++) {
                    sum += Math.max(v[k * p + j], 0);
                }
                out[k] += sum / p;
            }
        }
        return out;
    }

    Map<String, Object> profile(Principal p, String scope, LocalDate date, ExposureService.Ask ask, String dates) {
        ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", s.res().canonical());
        out.put("businessDate", s.date().date().toString());
        out.put("runId", s.date().book().runId());
        out.put("confidence", ask.q());
        out.put("profile", profileRows(s, "all".equalsIgnoreCase(dates)));
        out.put("flags", s.result().flags);
        svc.audit(p, "ccr-view", s, "profile");
        return out;
    }

    /** The profile as CSV: {@code tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll} (EXPOSURE_GUIDE.md section 19.3). */
    String profileCsv(Principal p, String scope, LocalDate date, ExposureService.Ask ask) {
        ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
        StringBuilder sb = new StringBuilder("tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll\n");
        for (Map<String, Object> r : profileRows(s, false)) {
            sb.append(r.get("tenor")).append(',').append(r.get("date")).append(',').append(r.get("t")).append(',').append(r.get("ee")).append(',')
                    .append(r.get("ene")).append(',').append(r.get("eee")).append(',').append(r.get("pfe")).append(',').append(r.get("pfe99")).append(',')
                    .append(r.get("eeUncoll")).append('\n');
        }
        svc.audit(p, "ccr-export", s, "profile.csv");
        return sb.toString();
    }

    /** The profile as JSON Lines (one grid date a line, the CSV's column names; docs/guides/DATA_GUIDE.md). */
    String profileJsonl(Principal p, String scope, LocalDate date, ExposureService.Ask ask) {
        ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
        String out = ExposureCaptures.profileJsonl(profileRows(s, false));
        svc.audit(p, "ccr-export", s, "profile.jsonl");
        return out;
    }

    /** The primary point a {@code date} parameter names: a grid date, a tenor ({@code 1Y}), or none (-1: the default of the figure). */
    static int primary(ExposureGrid g, String date) {
        if (date == null || date.isBlank()) {
            return -1;
        }
        String d = date.trim();
        for (int i = 0; i < g.primaryCount(); i++) {
            int k = g.primaryAt(i);
            if (g.date(k).toString().equals(d) || d.equalsIgnoreCase(g.tenor(k))) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "date " + date + " is not a primary grid date of the run (" + g.date(g.primaryAt(0))
                + " .. " + g.date(g.primaryAt(g.primaryCount() - 1)) + ", or a tenor such as 1Y)");
    }

    /** The exposure distribution at a grid date: a histogram, the quantiles, the mean and the share of paths with no exposure. */
    Map<String, Object> distribution(Principal p, String scope, LocalDate asOf, ExposureService.Ask ask, String date, int bins) {
        return distribution(p, scope, asOf, ask, date, bins, false);
    }

    /** The distribution at a date; with {@code bands}, also the path fan: the quantiles 5, 25, 50, 75 and 95% at every primary date. */
    Map<String, Object> distribution(Principal p, String scope, LocalDate asOf, ExposureService.Ask ask, String date, int bins, boolean bands) {
        ExposureService.Scoped s = svc.scoped(p, scope, asOf, ask);
        ExposureBook b = s.date().book();
        int i = primary(b.grid(), date);
        if (i < 0) {
            i = Math.max(0, s.result().stats.peakAt[s.result().stats.at(ask.q())]);
        }
        int pp = b.paths();
        double[] sorted = ExposureMath.sortedRow(s.result().e, i, pp);
        int n = bins <= 0 ? svc.props().histogramBins() : Math.min(500, bins);
        double lo = sorted[0];
        double hi = sorted[pp - 1];
        double w = hi > lo ? (hi - lo) / n : 1;
        long[] edges = new long[n + 1];
        int[] counts = new int[n];
        for (int k = 0; k <= n; k++) {
            edges[k] = Math.round(lo + k * w);
        }
        int zero = 0;
        for (double x : sorted) {
            counts[Math.min(n - 1, (int) ((x - lo) / w))]++;
            zero += x <= 0 ? 1 : 0;
        }
        Map<String, Object> q = new LinkedHashMap<>();
        for (double c : QUANTILES) {
            q.put(String.valueOf(c), Math.round(ExposureMath.quantileSorted(sorted, c, svc.props().rule())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", s.res().canonical());
        out.put("businessDate", s.date().date().toString());
        out.put("date", b.grid().date(b.grid().primaryAt(i)).toString());
        out.put("tenor", b.grid().tenor(b.grid().primaryAt(i)));
        out.put("histogram", Map.of("edges", edges, "counts", counts));
        out.put("quantiles", q);
        out.put("mean", Math.round(s.result().stats.ee[i]));
        out.put("zeroShare", (double) zero / pp);
        out.put("paths", pp);
        if (bands) {
            out.put("bands", ExposureExplorer.fan(s, svc.props().rule()));
        }
        svc.audit(p, "ccr-view", s, "distribution");
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- parts

    /** The default parts of a scope (NSET.md section 8): a group or legal entity its counterparties, a counterparty its netting sets, a
     * netting set or trades their trades, any other scope the netting sets it touches. */
    static String childOf(String kind) {
        return switch (kind) {
            case "group", "legal-entity" -> "counterparty";
            case "netting-set", "trades" -> "trade";
            case "trade" -> "none";
            default -> "netting-set";
        };
    }

    Map<String, Object> contributions(Principal p, String scope, LocalDate date, ExposureService.Ask ask, String by, String figure, String at) {
        return contributions(p, scope, date, ask, by, figure, at, false, false);
    }

    /**
     * The contributions; by trade with {@code alone}, each trade's peak PFE alone (uncollateralised) too, and with {@code profile} each
     * part's EE contribution at every primary date (ExposureExplorer).
     */
    Map<String, Object> contributions(Principal p, String scope, LocalDate date, ExposureService.Ask ask, String by, String figure, String at,
            boolean alone, boolean profile) {
        ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
        String mode = by == null || by.isBlank() || by.equalsIgnoreCase("child") ? childOf(s.kind()) : by.trim().toLowerCase(Locale.ROOT);
        String fig = figure == null || figure.isBlank() ? "PFE" : figure.trim().toUpperCase(Locale.ROOT);
        if (!List.of("EE", "EPE", "PFE").contains(fig)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "figure is EE, EPE or PFE, not " + figure);
        }
        if (!List.of("netting-set", "counterparty", "legal-entity", "trade", "asset", "none").contains(mode)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "by is child, netting-set, counterparty, legal-entity, trade or asset, not " + by);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", s.res().canonical());
        out.put("businessDate", s.date().date().toString());
        out.put("by", mode);
        out.put("figure", fig);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean detail = mode.equals("trade") || mode.equals("asset") || mode.equals("netting-set");
        boolean allowed = !detail || s.result().pieces.stream().allMatch(pc -> s.view().detailsSet(pc.set));
        if (mode.equals("none") || !allowed) {
            out.put("rows", rows);
            out.put("note", mode.equals("none") ? "a trade alone has no parts" : "your role sees this scope only in aggregate: no netting sets or trades");
            return out;
        }
        int i = primary(s.date().book().grid(), at);
        List<ExposureCalc.Part> parts = ExposureCalc.parts(s.result(), mode, svc.paths(s.date()));
        boolean truncated = parts.size() > svc.props().maxParts();
        List<ExposureCalc.Contribution> cs = ExposureCalc.contributions(s.result(), parts, fig, i);
        for (ExposureCalc.Contribution c : cs.subList(0, Math.min(cs.size(), svc.props().maxParts()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", c.key());
            r.put("scope", c.scope());
            r.put("label", c.kind().equals("counterparty") ? name(s, c.key(), p) : c.label());
            r.put("kind", c.kind());
            r.put("value", Math.round(c.value()));
            r.put("pct", c.pct());
            r.put("incremental", c.incremental() == null ? null : Math.round(c.incremental()));
            rows.add(r);
        }
        if (mode.equals("trade")) {
            explorer.trades(s, rows, alone);
        }
        if (profile) {
            ExposureExplorer.profiles(s, rows, parts);
        }
        ExposureMath.Stats st = s.result().stats;
        int qa = st.at(ask.q());
        out.put("date", fig.equals("EPE") ? null : s.date().book().grid().date(s.date().book().grid().primaryAt(i >= 0 ? i
                : fig.equals("PFE") ? Math.max(0, st.peakAt[qa]) : argmax(st.ee))).toString());
        out.put("total", Math.round(fig.equals("EE") ? (i >= 0 ? st.ee[i] : st.peakEe) : fig.equals("EPE") ? st.epe : st.peakPfe[qa]));
        out.put("rows", rows);
        out.put("parts", parts.size());
        out.put("truncated", truncated);
        svc.audit(p, "ccr-view", s, "contributions by=" + mode + " figure=" + fig);
        return out;
    }

    private String name(ExposureService.Scoped s, String cpty, Principal p) {
        return svc.name(s.date(), cpty, p);
    }

    private static int argmax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[best]) {
                best = i;
            }
        }
        return best;
    }

    /** For a counterparty or larger: each netting set's EPE, peaks and PFE contribution (rows the viewer may see). */
    private List<Map<String, Object>> byNettingSet(Principal p, ExposureService.Scoped s) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (s.result().pieces.size() < 2 && !List.of("counterparty", "group", "legal-entity", "bank").contains(s.kind())) {
            return out;
        }
        if (!s.result().pieces.stream().allMatch(pc -> s.view().detailsSet(pc.set))) {
            return out;
        }
        ExposureBook b = s.date().book();
        List<ExposureCalc.Part> parts = ExposureCalc.parts(s.result(), "netting-set", ids -> Map.of());
        Map<String, Double> pfe = new LinkedHashMap<>();
        for (ExposureCalc.Contribution c : ExposureCalc.contributions(s.result(), parts, "PFE", -1)) {
            pfe.put(c.key(), c.value());
        }
        double[] times = ExposureCalc.times(b.grid());
        for (ExposureCalc.Part pt : parts) {
            ExposureCalc.Piece pc = s.result().pieces.stream().filter(x -> b.set(x.set).id().equals(pt.key())).findFirst().orElseThrow();
            ExposureMath.Stats one = ExposureMath.statistics(pt.x(), new double[pt.x().length], null, times, b.paths(), s.result().stats.horizon,
                    new double[] {s.ask().q()}, pc.ce, svc.props().rule());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("nettingSet", pt.key());
            r.put("counterparty", b.set(pc.set).counterparty());
            r.put("label", pt.key() + " · " + svc.name(s.date(), b.set(pc.set).counterparty(), p));
            r.put("partial", !pc.whole);
            r.put("epe", Math.round(one.epe));
            r.put("peakEe", Math.round(one.peakEe));
            r.put("peakPfe", Math.round(one.peakPfe[0]));
            r.put("pfeContribution", Math.round(pfe.getOrDefault(pt.key(), 0.0)));
            out.add(r);
        }
        out.sort((x, y) -> Long.compare((Long) y.get("pfeContribution"), (Long) x.get("pfeContribution")));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- the tree

    /**
     * The groups, counterparties and netting sets of the date with each node's EPE and peak PFE (the explorer's left column): sets with
     * paths and sets the reference data knows without (marked {@code noPaths}); only what the viewer sees, at their level.
     */
    Map<String, Object> tree(Principal p, LocalDate date, ExposureService.Ask ask) {
        ExposureDate d = svc.date(date, ask.strict());
        ExposureAccess.View v = svc.access().view(p, d);
        ExposureBook b = d.book();
        Map<String, String> groupOf = new LinkedHashMap<>();
        d.groups().forEach((g, ms) -> ms.forEach(c -> groupOf.putIfAbsent(c, g)));
        Map<String, Map<String, Object>> cps = new java.util.TreeMap<>();
        for (ExposureDate.Known k : d.known().values()) {
            int s = b.setOrdinal(k.id());
            if (s >= 0 && !v.seesSet(s) || s < 0 && !v.full()) {
                continue;
            }
            Map<String, Object> cp = cps.computeIfAbsent(String.valueOf(k.counterparty()), c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("scope", "CPTY:" + c);
                m.put("id", c);
                m.put("label", svc.name(d, c, p));
                m.put("group", groupOf.get(c));
                m.put("children", new ArrayList<Map<String, Object>>());
                return m;
            });
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("scope", "NSET:" + k.id());
            n.put("id", k.id());
            n.put("legalEntity", k.legalEntity());
            n.put("members", k.members());
            n.put("csa", s >= 0 ? ExposureExplorer.csaLabel(b.set(s).csa()) : k.csa() == null ? "none" : k.csa());
            if (s < 0) {
                n.put("noPaths", true);
            } else if (v.detailsSet(s)) {
                figures(n, svc.scoped(p, "NSET:" + k.id(), d.date(), ask));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ch = (List<Map<String, Object>>) cp.get("children");
            ch.add(n);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, Map<String, Object>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> cp : cps.values()) {
            @SuppressWarnings("unchecked")
            boolean any = ((List<Map<String, Object>>) cp.get("children")).stream().anyMatch(n -> !Boolean.TRUE.equals(n.get("noPaths")));
            @SuppressWarnings("unchecked")
            List<String> les = ((List<Map<String, Object>>) cp.get("children")).stream().map(n -> String.valueOf(n.get("legalEntity"))).distinct()
                    .sorted().toList();
            cp.put("legalEntities", les);
            if (any) {
                ExposureService.Scoped cs = svc.scoped(p, "CPTY:" + cp.get("id"), d.date(), ask);
                figures(cp, cs);
                cp.put("homeLegalEntity", ExposureExplorer.homeLegalEntity(b, String.valueOf(cp.get("id"))));
                cp.put("limit", explorer.limit(cs, String.valueOf(cp.get("id"))));
            } else {
                cp.put("noPaths", true);
            }
            String g = (String) cp.get("group");
            Map<String, Object> gn = byGroup.computeIfAbsent(g == null ? "(no group)" : g, x -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("scope", g == null ? null : "GROUP:" + g);
                m.put("id", x);
                m.put("children", new ArrayList<Map<String, Object>>());
                groups.add(m);
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ch = (List<Map<String, Object>>) gn.get("children");
            ch.add(cp);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", d.date().toString());
        out.put("runId", b.runId());
        out.put("confidence", ask.q());
        out.put("bank", "BANK");
        out.put("tenors", ExposureExplorer.tenors(b.grid()));
        out.put("bankNode", nodeFigures(p, d, ask, "BANK", "Bank"));
        List<Map<String, Object>> les = new ArrayList<>();
        new java.util.TreeSet<>(cps.values().stream().flatMap(cp -> {
            @SuppressWarnings("unchecked")
            List<String> l = (List<String>) cp.get("legalEntities");
            return l.stream();
        }).filter(x -> !x.equals("null")).toList()).forEach(le -> {
            Map<String, Object> n = nodeFigures(p, d, ask, "LE:" + le, le);
            n.put("counterparties", cps.values().stream().filter(cp -> ((List<?>) cp.get("legalEntities")).contains(le)).map(cp -> cp.get("id")).toList());
            les.add(n);
        });
        out.put("legalEntities", les);
        out.put("groups", groups);
        out.put("nettingSetsWithPaths", b.sets().size());
        out.put("nettingSetsKnown", d.known().size());
        return out;
    }

    private static void figures(Map<String, Object> n, ExposureService.Scoped s) {
        ExposureMath.Stats st = s.result().stats;
        int qa = st.at(s.ask().q());
        ExposureGrid g = s.date().book().grid();
        n.put("epe", Math.round(st.epe));
        n.put("peakPfe", Math.round(st.peakPfe[qa]));
        n.put("peakPfeTenor", st.peakAt[qa] < 0 ? null : g.tenor(g.primaryAt(st.peakAt[qa])));
        n.put("peakEe", Math.round(st.peakEe));
        n.put("ee", ExposureExplorer.eeLine(st));
        n.put("flags", s.result().flags);
    }

    /** The bank's or a legal entity's node of the tree: its figures, or why an interactive answer cannot sum it (a batch job). */
    private Map<String, Object> nodeFigures(Principal p, ExposureDate d, ExposureService.Ask ask, String scope, String label) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("scope", scope);
        n.put("id", scope.contains(":") ? scope.substring(scope.indexOf(':') + 1) : scope);
        n.put("label", label);
        try {
            ExposureService.Scoped s = svc.scoped(p, scope, d.date(), ask);
            figures(n, s);
            n.put("nettingSets", s.result().pieces.size());
        } catch (DrishtiException e) {
            n.put("note", e.getMessage().replaceFirst("^DRS-\\d{4} ", ""));
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------------------- provenance

    /** How the figures were built: the run, the inputs, the method, the collateral, the engine, the viewer's view, the time. */
    Map<String, Object> provenance(ExposureService.Scoped s) {
        ExposureBook b = s.date().book();
        Map<String, Object> run = b.run();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", s.res().canonical());
        m.put("resolved", s.res().notes());
        m.put("unknown", s.res().unknown());
        m.put("nettingSets", s.result().pieces.size());
        m.put("wholeSets", s.result().wholeSets());
        m.put("partialSets", s.result().partialSets());
        m.put("trades", s.result().trades.size());
        m.put("hiddenSets", s.hiddenSets());
        m.put("hiddenTrades", s.hiddenTrades());
        m.put("runId", b.runId());
        m.put("model", run.get("MODEL"));
        m.put("modelVersion", run.get("MODEL_VERSION"));
        m.put("seed", run.get("SEED"));
        m.put("calibration", run.get("CALIBRATION"));
        m.put("valueBasis", run.get("VALUE_BASIS"));
        m.put("precision", run.get("PRECISION"));
        m.put("paths", b.paths());
        m.put("gridDates", b.grid().size());
        m.put("primaryDates", b.grid().primaryCount());
        m.put("lookbackDays", run.get("LOOKBACK_DAYS"));
        m.put("inputs", s.date().inputs());
        m.put("intake", s.date().intake());
        m.put("failedTrades", s.date().intake().get("failed"));
        m.put("proxiedTrades", s.date().intake().get("proxied"));
        List<String> warnings = new ArrayList<>(s.date().warnings());
        warnings.addAll(svc.loader().skipped().values());               // 2.9.5: a hierarchy skipped (DRS-5211), a warning only
        m.put("warnings", warnings);
        m.put("method", "Monte Carlo post-processing (EXPOSURE_GUIDE.md section 18): netting per path, " + s.ask().collateral() + " collateral, PFE "
                + (svc.props().rule() == ExposureMath.Rule.KTH ? "the ceil(P q)-th smallest of " + b.paths() + " paths" : "interpolated")
                + ", EPE and EEPE over min(1 year, the longest maturity) = " + round4(s.result().stats.horizon) + " years");
        m.put("contributions", "PFE: the " + svc.props().contributionWindow() + " paths around the quantile at the peak date, scaled to add up "
                + "to the peak; EE and EPE: each part's mean (Euler)");
        m.put("engine", "exposure engine in the server · drishti-compute " + ENGINE_VERSION);
        m.put("view", s.view().full() ? "every counterparty at trades level" : "restricted by counterparty (" + s.hiddenSets()
                + " netting sets outside your entitlement)");
        m.put("tier", tier(s));
        m.put("membershipHash", s.result().trades.digest());
        m.put("computedAt", Instant.now().toString());
        m.put("computeMs", s.computeMs());
        m.put("cache", s.cached() ? "hit" : "miss");
        m.put("loadedMs", s.date().loadMs());
        return m;
    }

    /** Where the answer's matrices came from: the hot file's float32 rows (summed in double), the heap, or the lake (cut sets). */
    static String tier(ExposureService.Scoped s) {
        String base = s.date().book().mapped() ? "hot file (float32 netting-set matrices, summed in float64)" : "memory (float64 netting-set matrices)";
        return s.result().partialSets() > 0 || s.result().standalone ? base + "; trade paths from the lake" : base;
    }

    static double round4(double x) {
        return Math.round(x * 10_000) / 10_000.0;
    }
}
