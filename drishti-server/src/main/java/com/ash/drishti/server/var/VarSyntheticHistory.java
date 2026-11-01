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
import com.ash.drishti.compute.var.VarMath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A synthetic official history for a demo server (VAR_GUIDE.md section 18.10): a demo lake holds one business date of vectors, so the
 * official records a year of backtesting and period statistics read are seeded from it, each record marked {@code synthetic: true} and
 * never published. For each scenario day {@code s_j} whose 250 days before it lie inside the date's scenario set, every node of the
 * newest official record gets the VaR and ES of today's members over the scenarios {@code s_(j-250) .. s_(j-1)}, the same at each
 * backtesting confidence, its risk-theoretical P&amp;L the scenario P&amp;L of {@code s_(j-1)} (the official record's convention), and its
 * stressed VaR and ES scaled by the day's VaR against today's. Off unless {@code drishti.regulatory.synthetic-history} is true: a bank's
 * server never seeds.
 */
final class VarSyntheticHistory {

    static final int WINDOW = 250;
    private final VarService svc;
    private final RegulatoryProperties props;

    VarSyntheticHistory(VarService svc, RegulatoryProperties props) {
        this.svc = svc;
        this.props = props;
    }

    /** Seeds the records of up to {@code days} business days before the oldest official record; says what it wrote. */
    @SuppressWarnings("unchecked")
    Map<String, Object> seed(int days) {
        if (!props.syntheticHistory()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "a synthetic official history is for demo servers only: it is off "
                    + "(drishti.regulatory.synthetic-history)");
        }
        VarOfficialStore store = svc.official();
        if (store.dates().isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no official record yet: capture the newest date first, then seed the history before it");
        }
        LocalDate newest = store.dates().last();
        LocalDate oldest = store.dates().first();
        Map<String, Object> rec = store.index(newest).orElseThrow();
        VarDate d = svc.date(newest);
        if (!d.date().equals(newest)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the newest official date " + newest + " has no vectors loaded");
        }
        List<LocalDate> sd = d.book().scenarioDates();
        int n = sd.size();
        Map<String, Map<String, Object>> nodes = (Map<String, Map<String, Object>>) rec.get("nodes");
        Map<String, double[]> vectors = new LinkedHashMap<>();
        for (String id : nodes.keySet()) {
            vectors.put(id, svc.scoped(null, "NODE:" + id, newest).vector());
        }
        List<Double> qs = props.backtesting().confidences();
        double conf = svc.props().confidence();
        double esConf = svc.props().esConfidence();
        String at = Instant.now().toString();
        int written = 0;
        LocalDate first = null;
        int lo = Math.max(WINDOW, n - Math.max(1, days));
        for (int j = lo; j < n; j++) {
            LocalDate day = sd.get(j);
            if (!day.isBefore(oldest) || store.index(day).isPresent()) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
                double[] v = vectors.get(e.getKey());
                if (v.length != n) {
                    continue;
                }
                double[] w = java.util.Arrays.copyOfRange(v, j - WINDOW, j);
                double var = VarMath.var(w, conf);
                Map<String, Object> f = new LinkedHashMap<>();
                for (String k : List.of("hierarchy", "level", "name", "parent", "limit")) {
                    f.put(k, e.getValue().get(k));
                }
                f.put("captureId", null);
                f.put("currency", VarDate.USD);
                f.put("var", var);
                f.put("es", VarMath.es(w, esConf));
                f.put("hypoPnl", Math.round(v[j - 1]));
                Map<String, Object> varAt = new LinkedHashMap<>();
                for (double q : qs) {
                    if (Math.abs(q - conf) > 1e-9) {
                        varAt.put(String.valueOf(q), VarMath.var(w, q));
                    }
                }
                f.put("varAt", varAt);
                double today = VarOfficialStore.num(e.getValue().get("var"));
                double scale = Double.isFinite(today) && today != 0 ? var / today : 1;
                for (String k : List.of("svar", "ses")) {
                    double x = VarOfficialStore.num(e.getValue().get(k));
                    if (Double.isFinite(x)) {
                        f.put(k, x * scale);
                    }
                }
                Object limit = e.getValue().get("limit");
                f.put("limitUse", limit instanceof Number l && l.doubleValue() > 0 ? var / l.doubleValue() : null);
                out.put(e.getKey(), f);
            }
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("businessDate", day.toString());
            index.put("currency", VarDate.USD);
            index.put("resultVersion", 1);
            index.put("computedAt", at);
            index.put("synthetic", true);
            index.put("note", "synthetic demo history: today's members over the " + WINDOW + " scenario days before " + day + " (seeded from the record of "
                    + newest + "), never published");
            index.put("horizon", "1D");
            index.put("confidence", conf);
            index.put("esConfidence", esConf);
            index.put("stressSet", rec.get("stressSet"));
            index.put("published", "not published: synthetic demo history");
            index.put("nodes", out);
            store.putIndex(day, index, VarDate.USD);
            written++;
            first = first == null ? day : first;
        }
        svc.audit(VarOfficialStore.OWNER, "var-synthetic-history", newest.toString(), "records=" + written);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("written", written);
        m.put("from", first == null ? null : first.toString());
        m.put("to", oldest.toString());
        m.put("records", store.dates().size());
        m.put("note", written == 0 ? "nothing to seed: every scenario day with a full " + WINDOW + "-day window before it has a record"
                : "synthetic demo records, marked synthetic and never published");
        return m;
    }
}
