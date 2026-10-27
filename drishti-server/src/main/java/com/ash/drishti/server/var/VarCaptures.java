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
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Captured VaR (VAR_GUIDE.md section 6): a scope computed now and kept, immutable, with everything needed to read it back exactly
 * as it was even after the data changes: the numbers (VaR, ES, each member's component VaR, the worst 25 scenarios), the full vector,
 * the resolved member trades, and the provenance (scope, date, data version, scenario set, method and rank rule, engine, viewer, time,
 * a note). Captures are listed per scope, reopened, compared (two captures, or a capture and now) and exported as CSV.
 */
final class VarCaptures {

    private final VarService svc;
    private final VarViews views;

    VarCaptures(VarService svc, VarViews views) {
        this.svc = svc;
        this.views = views;
    }

    Map<String, Object> capture(Principal p, String scope, LocalDate date, String note) {
        VarService.Scoped s = svc.scoped(p, scope, date);
        VarBook b = s.date().book();
        Map<String, Object> head = views.headline(p, scope, date);
        Map<String, Object> contrib = views.contributions(p, scope, date, "child", null);
        Map<String, Object> path = views.path(p, scope, date, 0, 25);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("scope", scope);
        c.put("canonical", s.res().canonical());
        c.put("label", s.label());
        c.put("kind", s.kind());
        c.put("businessDate", s.date().date().toString());
        c.put("capturedAt", Instant.now().toString());
        c.put("owner", p.user());
        c.put("sharedWith", List.of());
        c.put("note", note == null ? "" : note.length() > 1000 ? note.substring(0, 1000) : note);
        c.put("var", head.get("var"));
        c.put("es", head.get("es"));
        c.put("k", head.get("k"));
        c.put("esCount", head.get("esCount"));
        c.put("confidence", head.get("confidence"));
        c.put("esConfidence", head.get("esConfidence"));
        c.put("limit", head.get("limit"));
        c.put("sumStandalone", head.get("sumStandalone"));
        c.put("diversification", head.get("diversification"));
        c.put("components", contrib.get("rows"));
        c.put("componentsBy", contrib.get("by"));
        c.put("worst", path.get("worst"));
        long[] vector = new long[s.vector().length];
        for (int j = 0; j < vector.length; j++) {
            vector[j] = Math.round(s.vector()[j]);
        }
        c.put("vector", vector);
        c.put("scenarioSet", b.scenarioSet());
        c.put("scenarioDates", b.scenarioDates().stream().map(LocalDate::toString).toList());
        List<String> members = new ArrayList<>();
        s.set().forEach(o -> members.add(b.tradeId(o)));
        c.put("members", members);
        c.put("memberCount", members.size());
        c.put("dataVersion", s.date().version());
        c.put("provenance", head.get("provenance"));
        String id = svc.saved().capture(p, c, s.date().date().toString());
        svc.audit(p, "var-capture", s, "capture=" + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("capturedAt", c.get("capturedAt"));
        out.put("var", c.get("var"));
        out.put("es", c.get("es"));
        out.put("memberCount", members.size());
        return out;
    }

    /** A capture exactly as it was written (the full vector and members included). */
    Map<String, Object> get(Principal p, String id) {
        return svc.saved().capture(p, id).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no capture " + id + " you may read"));
    }

    List<Map<String, Object>> list(Principal p, String scope) {
        String canonical = null;
        if (scope != null && !scope.isBlank()) {
            try {
                canonical = com.ash.drishti.compute.var.ScopeExpr.parse(scope).toString();
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(svc.saved().captures(p, canonical));
        if (canonical != null && svc.access().seesAll(p)) {               // a node's official end-of-day captures too
            try {
                String node = svc.scoped(p, scope, null).res().node();
                if (node != null) {
                    out.addAll(svc.official().captures(node));
                }
            } catch (DrishtiException e) {
                // no date loaded: the people's captures only
            }
            out.sort((x, y) -> String.valueOf(y.get("capturedAt")).compareTo(String.valueOf(x.get("capturedAt"))));
        }
        return out;
    }

    /**
     * Two captures side by side, or a capture and now ({@code b = now}: the capture's scope computed on the newest date): the headline
     * figures, the members added and removed, and each component's change.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> compare(Principal p, String a, String b) {
        Map<String, Object> ca = get(p, a);
        Map<String, Object> cb;
        if (b == null || b.isBlank() || b.equalsIgnoreCase("now")) {
            String scope = String.valueOf(ca.get("scope"));
            Map<String, Object> head = views.headline(p, scope, null);
            Map<String, Object> contrib = views.contributions(p, scope, null, "child", null);
            cb = new LinkedHashMap<>();
            cb.put("id", "now");
            cb.put("scope", scope);
            cb.put("businessDate", head.get("businessDate"));
            cb.put("capturedAt", Instant.now().toString());
            cb.put("var", head.get("var"));
            cb.put("es", head.get("es"));
            cb.put("components", contrib.get("rows"));
            VarService.Scoped s = svc.scoped(p, scope, null);
            List<String> members = new ArrayList<>();
            s.set().forEach(o -> members.add(s.date().book().tradeId(o)));
            cb.put("members", members);
            cb.put("memberCount", members.size());
        } else {
            cb = get(p, b);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("a", summary(ca));
        out.put("b", summary(cb));
        out.put("varChange", num(cb.get("var")) - num(ca.get("var")));
        out.put("esChange", num(cb.get("es")) - num(ca.get("es")));
        Set<String> ma = new HashSet<>(svc.members(ca));
        Set<String> mb = new HashSet<>(svc.members(cb));
        out.put("membersAdded", mb.stream().filter(x -> !ma.contains(x)).sorted().limit(200).toList());
        out.put("membersRemoved", ma.stream().filter(x -> !mb.contains(x)).sorted().limit(200).toList());
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) ca.getOrDefault("components", List.of())) {
            Map<String, Object> m = rows.computeIfAbsent(String.valueOf(r.get("key")), k -> new LinkedHashMap<>(Map.of("key", k)));
            m.put("label", r.get("label"));
            m.put("a", r.get("componentVar"));
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) cb.getOrDefault("components", List.of())) {
            Map<String, Object> m = rows.computeIfAbsent(String.valueOf(r.get("key")), k -> new LinkedHashMap<>(Map.of("key", k)));
            m.putIfAbsent("label", r.get("label"));
            m.put("b", r.get("componentVar"));
        }
        List<Map<String, Object>> comps = new ArrayList<>(rows.values());
        comps.forEach(m -> m.put("change", num(m.get("b")) - num(m.get("a"))));
        comps.sort((x, y) -> Double.compare(Math.abs(num(y.get("change"))), Math.abs(num(x.get("change")))));
        out.put("components", comps);
        return out;
    }

    private static Map<String, Object> summary(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : List.of("id", "scope", "label", "businessDate", "capturedAt", "owner", "note", "var", "es", "memberCount", "dataVersion")) {
            if (c.containsKey(f)) {
                m.put(f, c.get(f));
            }
        }
        return m;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** A capture as CSV: its provenance as comment lines, then the vector by scenario date, or its components. */
    @SuppressWarnings("unchecked")
    String csv(Principal p, String id, String part) {
        Map<String, Object> c = get(p, id);
        StringBuilder sb = new StringBuilder();
        for (String f : List.of("id", "scope", "label", "businessDate", "capturedAt", "owner", "note", "var", "es", "dataVersion", "scenarioSet")) {
            sb.append("# ").append(f).append(": ").append(String.valueOf(c.get(f)).replace('\n', ' ')).append('\n');
        }
        if ("components".equals(part)) {
            sb.append("key,label,standalone_var,component_var,component_es,pct_of_var,incremental_var\n");
            for (Map<String, Object> r : (List<Map<String, Object>>) c.getOrDefault("components", List.of())) {
                sb.append(cell(r.get("key"))).append(',').append(cell(r.get("label"))).append(',').append(r.get("standaloneVar")).append(',')
                        .append(r.get("componentVar")).append(',').append(r.get("componentEs")).append(',').append(r.get("pct")).append(',')
                        .append(r.get("incrementalVar")).append('\n');
            }
            return sb.toString();
        }
        sb.append("scenario_date,pnl\n");
        List<Object> dates = (List<Object>) c.getOrDefault("scenarioDates", List.of());
        List<Object> v = (List<Object>) c.getOrDefault("vector", List.of());
        Map<Integer, Object> byIndex = new HashMap<>();
        for (int i = 0; i < v.size(); i++) {
            byIndex.put(i, v.get(i));
        }
        for (int i = 0; i < dates.size(); i++) {
            sb.append(dates.get(i)).append(',').append(byIndex.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * A capture as JSON Lines (2.9.4, docs/guides/DATA_FORMATS.md): the rows of its CSV, one a line, keyed as the CSV's header ({@code scenario_date, pnl};
     * {@code part=components}: the component rows). The capture's own fields are its JSON (GET /captures/{id}).
     */
    @SuppressWarnings("unchecked")
    String jsonl(Principal p, String id, String part) {
        Map<String, Object> c = get(p, id);
        StringBuilder sb = new StringBuilder();
        if ("components".equals(part)) {
            for (Map<String, Object> r : (List<Map<String, Object>>) c.getOrDefault("components", List.of())) {
                Map<String, Object> line = new java.util.LinkedHashMap<>();
                line.put("key", r.get("key"));
                line.put("label", r.get("label"));
                line.put("standalone_var", r.get("standaloneVar"));
                line.put("component_var", r.get("componentVar"));
                line.put("component_es", r.get("componentEs"));
                line.put("pct_of_var", r.get("pct"));
                line.put("incremental_var", r.get("incrementalVar"));
                sb.append(com.ash.drishti.server.dist.JsonlWriter.line(line));
            }
            return sb.toString();
        }
        List<Object> dates = (List<Object>) c.getOrDefault("scenarioDates", List.of());
        List<Object> v = (List<Object>) c.getOrDefault("vector", List.of());
        for (int i = 0; i < dates.size(); i++) {
            Map<String, Object> line = new java.util.LinkedHashMap<>();
            line.put("scenario_date", dates.get(i));
            line.put("pnl", i < v.size() ? v.get(i) : null);
            sb.append(com.ash.drishti.server.dist.JsonlWriter.line(line));
        }
        return sb.toString();
    }

    private static String cell(Object o) {
        String s = String.valueOf(o);
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
