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

import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the CCR lens draws beside the pivot engine's grid (EXPOSURE_GUIDE.md section 30.5): the headline strip of landing A (the bank's peak
 * PFE, EPE and EEPE, the counterparties over and near their limits by tenor bucket, the run) and the fan of a scope (EE and PFE by primary
 * date with the counterparty's limit as a step line by bucket, the binding tenor and its headroom). Every figure is the exposure engine's
 * for the scope as the person sees it ({@link ExposureService#scoped}); nothing here aggregates on its own.
 */
final class CcrCubeExtras {

    private final CcrPivotProvider provider;

    CcrCubeExtras(CcrPivotProvider provider) {
        this.provider = provider;
    }

    CcrPivotProvider provider() {
        return provider;
    }

    private ExposureService svc() {
        return provider.exposure();
    }

    /** The headline strip of a view of source ccr: the bank as the person sees it, and each counterparty's use by bucket. */
    Map<String, Object> headline(Principal p, PivotView view) {
        if (!CcrPivotProvider.SOURCE.equals(view.source())) {
            throw PivotView.bad("the CCR lens reads views of source ccr, not " + view.source());
        }
        ExposureService.Ask ask = provider.ask(view.options());
        CcrPivotProvider.Opened o = provider.open(p, view.date());
        ExposureService.Scoped bank = svc().scoped(p, "BANK", o.date().date(), ask);
        ExposureMath.Stats st = bank.result().stats;
        ExposureBook b = o.date().book();
        ExposureGrid g = b.grid();
        int at = Math.max(0, st.at(ask.q()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", "BANK");
        m.put("date", o.date().date().toString());
        m.put("confidence", ask.q());
        m.put("collateral", ask.collateral());
        m.put("peakPfe", st.peakPfe[at]);
        m.put("peakTenor", st.peakAt[at] < 0 ? null : g.tenor(g.primaryAt(st.peakAt[at])));
        int a99 = st.at(0.99);
        m.put("pfe99", a99 < 0 ? null : st.peakPfe[a99]);
        m.put("epe", st.epe);
        m.put("eepe", st.eepe);
        m.put("ce", st.ce);
        m.put("run", b.runId());
        m.put("runNote", b.model().text() + " · " + b.paths() + " paths · " + g.primaryCount() + " dates · " + ask.collateral() + " collateral");
        m.put("nettingSets", o.members().size());
        m.put("flags", bank.result().flags);
        List<Map<String, Object>> over = new ArrayList<>();
        List<Map<String, Object>> near = new ArrayList<>();
        int withLimit = 0;
        int counterparties = 0;
        if (o.shown()) {
            Map<String, Set<String>> byCpty = new LinkedHashMap<>();
            for (CcrPivotProvider.Member mem : o.members()) {
                byCpty.computeIfAbsent(mem.coords().get("COUNTERPARTY"), k -> new TreeSet<>()).add(mem.set());
            }
            counterparties = byCpty.size();
            for (Map.Entry<String, Set<String>> e : byCpty.entrySet()) {
                ExposureDate.Limit lim = o.date().limits().get(e.getKey());
                if (lim == null) {
                    continue;
                }
                withLimit++;
                ExposureMath.Stats cs;
                try {
                    cs = svc().scoped(p, "NSET:" + String.join(",", e.getValue()), o.date().date(), ask).result().stats;
                } catch (DrishtiException ex) {
                    continue;                                           // no paths for its sets: the board says so in its own row
                }
                double worst = -1;
                String bucket = null;
                for (CcrCubeProperties.Bucket bk : provider.buckets()) {
                    int[] in = provider.dates(g, Set.of(bk.label()));
                    if (in.length == 0) {
                        continue;
                    }
                    double use = provider.used(cs, in, ask.q()) / lim.amount();
                    if (use > worst) {
                        worst = use;
                        bucket = bk.label();
                    }
                }
                if (bucket == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("counterparty", e.getKey());
                row.put("bucket", bucket);
                row.put("use", worst);
                row.put("limit", lim.amount());
                if (worst > 1) {
                    over.add(row);
                } else if (worst >= provider.props().warningAt()) {
                    near.add(row);
                }
            }
        }
        java.util.Comparator<Map<String, Object>> byUse = (x, y) -> Double.compare((Double) y.get("use"), (Double) x.get("use"));
        over.sort(byUse);
        near.sort(byUse);
        int n = provider.props().headlineNames();
        m.put("counterparties", counterparties);
        m.put("withLimit", withLimit);
        m.put("overCount", over.size());
        m.put("over", over.subList(0, Math.min(n, over.size())));
        m.put("nearCount", near.size());
        m.put("near", near.subList(0, Math.min(n, near.size())));
        m.put("warningAt", provider.props().warningAt());
        m.put("limitConfidence", svc().props().limitConfidence());
        m.put("buckets", new ArrayList<>(provider.props().buckets().keySet()));
        if (!o.shown()) {
            m.put("note", "your role sees some netting sets only in aggregate: the bank's figures only");
        }
        return m;
    }

    /**
     * The fan of a scope (A6): EE and PFE at the confidence by primary date, the 5% to 95% band where computed, the counterparty's limit as a
     * step line by bucket, the headroom at each date, the binding tenor (the least headroom) and the peak.
     */
    Map<String, Object> fan(Principal p, String scope, java.time.LocalDate date, String q, String collateral) {
        Map<String, String> opts = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            opts.put("q", q);
        }
        if (collateral != null && !collateral.isBlank()) {
            opts.put("collateral", collateral);
        }
        ExposureService.Ask ask = provider.ask(opts);
        String sc = ExposureService.scopeText(scope == null || scope.isBlank() ? "BANK" : scope);
        ExposureService.Scoped s = svc().scoped(p, sc, date, ask);
        ExposureMath.Stats st = s.result().stats;
        ExposureDate d = s.date();
        ExposureGrid g = d.book().grid();
        int at = Math.max(0, st.at(ask.q()));
        Set<String> sets = new LinkedHashSet<>();
        s.result().pieces.forEach(pc -> sets.add(d.book().set(pc.set).id()));
        ExposureDate.Limit lim = CcrPivotProvider.limitOf(d, sets);
        List<Map<String, Object>> points = new ArrayList<>();
        double minHead = Double.POSITIVE_INFINITY;
        String binding = null;
        int la = st.at(svc().props().limitConfidence());
        for (int i = 0; i < g.primaryCount(); i++) {
            double t = g.time(g.primaryAt(i));
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("tenor", g.tenor(g.primaryAt(i)));
            pt.put("t", t);
            pt.put("ee", st.ee[i]);
            pt.put("pfe", st.pfe[at][i]);
            pt.put("ene", st.ene[i]);
            String bucket = null;
            for (CcrCubeProperties.Bucket bk : provider.buckets()) {
                if (bk.holds(t)) {
                    bucket = bk.label();
                    break;
                }
            }
            pt.put("bucket", bucket);
            if (lim != null) {
                double used = st.pfe[la < 0 ? at : la][i];
                double head = lim.amount() - used;
                pt.put("limit", lim.amount());
                pt.put("headroom", head);
                if (head < minHead) {
                    minHead = head;
                    binding = (String) pt.get("tenor");
                }
            }
            points.add(pt);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", s.res().canonical());
        m.put("label", s.label());
        m.put("date", d.date().toString());
        m.put("confidence", ask.q());
        m.put("collateral", ask.collateral());
        m.put("points", points);
        m.put("peakPfe", st.peakPfe[at]);
        m.put("peakTenor", st.peakAt[at] < 0 ? null : g.tenor(g.primaryAt(st.peakAt[at])));
        m.put("epe", st.epe);
        m.put("limit", lim == null ? null : lim.amount());
        m.put("limitId", lim == null ? null : lim.id());
        m.put("bindingTenor", binding);
        m.put("minHeadroom", lim == null ? null : minHead);
        m.put("limitNote", lim == null ? "no single counterparty's stored credit limit applies to this scope" : "the stored credit limit (credit-limit "
                + lim.id() + ") applies to every tenor bucket, compared with the PFE at " + Math.round(svc().props().limitConfidence() * 100) + "%");
        m.put("flags", s.result().flags);
        return m;
    }
}
