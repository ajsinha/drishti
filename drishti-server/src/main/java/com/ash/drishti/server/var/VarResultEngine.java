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

import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.EngineException;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The computed kind {@code var-result} (VAR_GUIDE.md section 8, as built in 2.8.0): a scope's VaR answered by the server's VaR engine through
 * the market-risk pack's reactive connector {@code var-engine} ({@code plugin: reactive}, {@code engine.type: java}), so VaR opens as an
 * ordinary kind: {@code VRES DESK:RATES <GO>}, {@code /v/var-result/DESK:RATES}, the entities API, the CLI, search and the AI tools, with
 * the same entitlements as the explorer (the answer is computed as the viewer: their nodes, their masks, their audit entry). Java keeps
 * the additive aggregation here; the Python engine ({@code var-whatif}) reads this kind for what it adds on top.
 *
 * <p>The id is the scope text ({@code DESK:RATES}, {@code BANK - DESK:CREDIT}); a {@code ~} inside a node id is written {@code %7E}
 * ({@code NODE:MGMT%7EDESK-RATES}), as every computed id writes it. The parameter {@code ccy} (2.9.4; default USD) asks it in a reporting
 * currency ({@code VRES DESK:RATES~ccy=EUR}); {@code CURRENCY} says which. The record: the headline in UPPER_SNAKE ({@code SCOPE},
 * {@code CANONICAL}, {@code SCOPE_KIND}, {@code LABEL}, {@code NODE}, {@code BUSINESS_DATE}, {@code VAR}, {@code ES}, {@code CONFIDENCE},
 * {@code ES_CONFIDENCE}, {@code K}, {@code ES_COUNT}, {@code SCENARIOS}, {@code LIMIT}, {@code LIMIT_USE}, {@code SUM_STANDALONE},
 * {@code DIVERSIFICATION}, {@code BACKTEST}), the default parts ({@code CHILDREN}), the 25 worst scenarios ({@code WORST}), the scope's
 * vector ({@code PNL}, unrounded) and {@code PROVENANCE}; the tables {@code children} and {@code worst} for panels.
 *
 * <p>Made by the reactive plugin with no arguments, so it finds the engine through {@link #install}: the server installs it when the
 * VaR engine starts (as {@link VarPositions} is).
 */
public final class VarResultEngine implements ComputeEngine {

    public static final String KIND = "var-result";
    private static final AtomicReference<VarViews> INSTALLED = new AtomicReference<>();

    static void install(VarViews views) {
        INSTALLED.set(views);
    }

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("var-engine", VarViews.ENGINE_VERSION, List.of(KIND));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) throws Exception {
        VarViews views = INSTALLED.get();
        if (views == null) {
            throw new EngineException("the VaR engine is not running on this server (drishti.var.enabled)");
        }
        String scope = req.id().trim();
        if (scope.isEmpty()) {
            throw new EngineException("give a scope as the id: BANK, DESK:RATES, POS:<id>, PORT:<id>, BANK - DESK:CREDIT");
        }
        Principal p = new Principal(req.viewer().user(), req.viewer().roles());
        LocalDate date = req.asOf() == null ? null : req.asOf().businessDate();
        Object asked = req.params().get("ccy");
        String ccy = asked == null || String.valueOf(asked).isBlank() ? null : String.valueOf(asked);
        Map<String, Object> head;
        Map<String, Object> parts;
        Map<String, Object> path;
        VarService.Scoped s;
        try {
            head = views.headline(p, scope, date, ccy);
            parts = views.contributions(p, scope, date, "child", null, ccy);
            path = views.path(p, scope, date, 0, 1, ccy);
            s = views.service().scoped(p, scope, date, ccy);
        } catch (DrishtiException e) {
            throw new EngineException(e.getMessage());
        }
        progress.fraction(1, 1);
        Map<String, Object> row = new LinkedHashMap<>();
        for (String f : List.of("scope", "canonical", "kind", "label", "node", "businessDate", "currency", "var", "es", "confidence", "esConfidence", "k",
                "esCount", "scenarios", "limit", "limitUse", "sumStandalone", "diversification", "backtest")) {
            row.put(f.equals("kind") ? "SCOPE_KIND" : snake(f), upper(head.get(f)));
        }
        List<Map<String, Object>> children = new ArrayList<>();
        if (parts.get("rows") instanceof List<?> rows) {
            for (Object r : rows) {
                if (r instanceof Map<?, ?> m) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    for (String f : List.of("key", "scope", "label", "kind", "standaloneVar", "componentVar", "componentEs", "incrementalVar", "pct")) {
                        c.put(snake(f), m.get(f));
                    }
                    children.add(c);
                }
            }
        }
        row.put("CHILDREN", children);
        List<Map<String, Object>> worst = new ArrayList<>();
        if (path.get("worst") instanceof List<?> ws) {
            for (Object w : ws) {
                worst.add(upper(w) instanceof Map<?, ?> m ? cast(m) : Map.of());
            }
        }
        row.put("WORST", worst);
        List<Double> pnl = new ArrayList<>(s.vector().length);
        for (double v : s.vector()) {
            pnl.add(v);
        }
        row.put("PNL", pnl);
        row.put("PROVENANCE", upper(head.get("provenance")));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("engine", "var-engine");
        meta.put("tier", VarViews.tier(s));
        return new EngineResult(List.of(row), Map.of("children", children, "worst", worst), meta);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** camelCase to UPPER_SNAKE: {@code sumStandalone} is {@code SUM_STANDALONE}, {@code kupiecPValue} {@code KUPIEC_P_VALUE}. */
    static String snake(String camel) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                b.append('_');
            }
            b.append(Character.toUpperCase(c));
        }
        return b.toString().toUpperCase(Locale.ROOT);
    }

    /** A map's keys (and its nested maps' and lists' of maps) in UPPER_SNAKE; other values as they are. */
    static Object upper(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(snake(String.valueOf(k)), upper(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(x -> out.add(upper(x)));
            return out;
        }
        return v;
    }
}
