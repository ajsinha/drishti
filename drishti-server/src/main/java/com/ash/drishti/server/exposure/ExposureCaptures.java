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
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captured exposure (EXPOSURE_GUIDE.md section 19.2): a scope's answer computed now and kept, immutable: its figures, its profile, its netting sets'
 * figures, the trades it covered and how it was made (the run, the inputs' versions, the collateral model, the confidence). Never the
 * trade paths. Listed per scope, reopened, compared (two captures, or a capture and now) and exported as CSV (the {@code # field:
 * value} lines, then the profile).
 */
final class ExposureCaptures {

    private static final List<String> FIGURES = List.of("ce", "epe", "eepe", "peakEe", "peakPfe", "peakPfeDate", "peakPfe99", "eadImm");
    private final ExposureService svc;
    private final ExposureViews views;

    ExposureCaptures(ExposureService svc, ExposureViews views) {
        this.svc = svc;
        this.views = views;
    }

    Map<String, Object> capture(Principal p, String scope, LocalDate date, ExposureService.Ask ask, String note) {
        Map<String, Object> head = views.scope(p, scope, date, ask);
        ExposureService.Scoped s = svc.scoped(p, scope, date, ask);
        Map<String, Object> c = new LinkedHashMap<>();
        for (String f : List.of("scope", "canonical", "kind", "label", "businessDate", "runId", "paths", "confidence", "collateralModel")) {
            c.put(f, head.get(f));
        }
        c.put("capturedAt", Instant.now().toString());
        c.put("owner", p.user());
        c.put("sharedWith", List.of());
        c.put("note", note == null ? "" : note.length() > 1000 ? note.substring(0, 1000) : note);
        FIGURES.forEach(f -> c.put(f, head.get(f)));
        c.put("profile", head.get("profile"));
        c.put("byNettingSet", head.get("byNettingSet"));
        c.put("flags", head.get("flags"));
        List<String> members = new ArrayList<>();
        if (s.view().seesTrades(s.result().trades)) {
            s.result().trades.forEach(t -> members.add(s.date().book().trade(t).id()));
        }
        c.put("members", members);
        c.put("memberCount", s.result().trades.size());
        c.put("dataVersion", s.date().version());
        c.put("inputs", s.date().inputs());
        c.put("provenance", head.get("provenance"));
        String id = svc.official().capture(p, c, s.date().date().toString());
        svc.audit(p, "ccr-capture", s, "capture=" + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("capturedAt", c.get("capturedAt"));
        out.put("epe", c.get("epe"));
        out.put("peakPfe", c.get("peakPfe"));
        out.put("memberCount", c.get("memberCount"));
        return out;
    }

    Map<String, Object> get(Principal p, String id) {
        return svc.official().capture(p, id).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure capture " + id
                + " you may read"));
    }

    List<Map<String, Object>> list(Principal p, String scope) {
        String canonical = null;
        if (scope != null && !scope.isBlank()) {
            try {
                canonical = com.ash.drishti.compute.var.ScopeExpr.parse(scope).toString();
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
            }
        }
        return svc.official().captures(p, canonical);
    }

    /** Two captures side by side, or a capture and now ({@code b = now}): the figures, the profile and the members added and removed. */
    @SuppressWarnings("unchecked")
    Map<String, Object> compare(Principal p, String a, String b) {
        Map<String, Object> ca = get(p, a);
        Map<String, Object> cb;
        if (b == null || b.isBlank() || b.equalsIgnoreCase("now")) {
            String scope = String.valueOf(ca.get("scope"));
            ExposureService.Ask ask = ExposureService.Ask.of(svc.props(), String.valueOf(ca.get("confidence")), String.valueOf(ca.get("collateralModel")),
                    null, null);
            cb = new LinkedHashMap<>(views.scope(p, scope, null, ask));
            cb.put("id", "now");
            cb.put("capturedAt", Instant.now().toString());
            ExposureService.Scoped s = svc.scoped(p, scope, null, ask);
            List<String> members = new ArrayList<>();
            if (s.view().seesTrades(s.result().trades)) {
                s.result().trades.forEach(t -> members.add(s.date().book().trade(t).id()));
            }
            cb.put("members", members);
        } else {
            cb = get(p, b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", summary(ca));
        out.put("b", summary(cb));
        Map<String, Object> change = new LinkedHashMap<>();
        for (String f : List.of("ce", "epe", "eepe", "peakEe", "peakPfe", "eadImm")) {
            change.put(f, num(cb.get(f)) - num(ca.get(f)));
        }
        out.put("change", change);
        Set<String> ma = new HashSet<>(ca.get("members") instanceof List<?> l ? (List<String>) l : List.of());
        Set<String> mb = new HashSet<>(cb.get("members") instanceof List<?> l ? (List<String>) l : List.of());
        out.put("membersAdded", mb.stream().filter(x -> !ma.contains(x)).sorted().limit(200).toList());
        out.put("membersRemoved", ma.stream().filter(x -> !mb.contains(x)).sorted().limit(200).toList());
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Object o : ca.get("profile") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> r = (Map<String, Object>) o;
            rows.computeIfAbsent(String.valueOf(r.get("tenor")), k -> new LinkedHashMap<>(Map.of("tenor", k))).put("a", r.get("pfe"));
        }
        for (Object o : cb.get("profile") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> r = (Map<String, Object>) o;
            rows.computeIfAbsent(String.valueOf(r.get("tenor")), k -> new LinkedHashMap<>(Map.of("tenor", k))).put("b", r.get("pfe"));
        }
        List<Map<String, Object>> prof = new ArrayList<>(rows.values());
        prof.forEach(m -> m.put("change", num(m.get("b")) - num(m.get("a"))));
        out.put("pfeProfile", prof);
        return out;
    }

    private static Map<String, Object> summary(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : List.of("id", "scope", "label", "businessDate", "runId", "capturedAt", "owner", "note", "confidence", "collateralModel", "ce",
                "epe", "eepe", "peakEe", "peakPfe", "peakPfeDate", "memberCount", "dataVersion")) {
            if (c.containsKey(f)) {
                m.put(f, c.get(f));
            }
        }
        return m;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** A capture as CSV: its fields as {@code # field: value} lines, then its profile (EXPOSURE_GUIDE.md section 19.3). */
    @SuppressWarnings("unchecked")
    String csv(Principal p, String id) {
        Map<String, Object> c = get(p, id);
        StringBuilder sb = new StringBuilder();
        for (String f : List.of("id", "scope", "label", "businessDate", "runId", "capturedAt", "owner", "note", "confidence", "collateralModel", "ce",
                "epe", "eepe", "peakPfe", "peakPfeDate", "eadImm", "dataVersion")) {
            sb.append("# ").append(f).append(": ").append(String.valueOf(c.get(f)).replace('\n', ' ')).append('\n');
        }
        sb.append("tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll\n");
        for (Object o : c.get("profile") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> r = (Map<String, Object>) o;
            sb.append(r.get("tenor")).append(',').append(r.get("date")).append(',').append(r.get("t")).append(',').append(r.get("ee")).append(',')
                    .append(r.get("ene")).append(',').append(r.get("eee")).append(',').append(r.get("pfe")).append(',').append(r.get("pfe99")).append(',')
                    .append(r.get("eeUncoll")).append('\n');
        }
        return sb.toString();
    }
}
