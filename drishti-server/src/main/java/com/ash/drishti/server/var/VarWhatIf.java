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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.compute.ComputeRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.reactive.Answer;
import com.ash.drishti.compute.reactive.ReactiveConnector;
import com.ash.drishti.compute.reactive.ReactiveHub;
import com.ash.drishti.compute.reactive.Sandboxes;
import com.ash.drishti.server.compute.Viewers;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-deal what-if of the basket tray (VAR_GUIDE.md section 8.5): hypothetical trades, each a copy of a booked trade's P&amp;L vector
 * scaled by a factor ({@code -1}: its unwind), added to a scope; the scope's VaR and ES before and after, and each trade's incremental and
 * component VaR. The trades' vectors are read here as the person (a trade they may not see one by one is refused), put in a private
 * sandbox, and the Python VaR engine (the reactive connector {@code var-whatif}, compute pool {@code var}) is asked as them; the sandbox
 * is deleted after. Without the Python engine the answer is {@code 503 DRS-5205}, in words.
 *
 * <pre>
 *   POST /api/v1/var/what-if {"scope": "BASKET:asha/unwind", "items": [{"copyOf": "MX-30000121", "factor": -1}]}
 * </pre>
 */
final class VarWhatIf {

    static final String CONNECTOR = "var-whatif";
    static final String KIND = "var-whatif";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int MAX_ITEMS = 50;
    private final VarService svc;

    VarWhatIf(VarService svc) {
        this.svc = svc;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> whatIf(Principal p, Map<String, Object> body, LocalDate date) {
        String scope = String.valueOf(body.getOrDefault("scope", "")).trim();
        if (scope.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if names its scope: {\"scope\": \"DESK:RATES\", \"items\": [{\"copyOf\": \"MX-30000121\", \"factor\": -1}]}");
        }
        List<Map<String, Object>> asked = body.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (asked.isEmpty() || asked.size() > MAX_ITEMS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if has 1 to " + MAX_ITEMS + " items: [{\"copyOf\": <a booked trade>, \"factor\": 1}]");
        }
        VarService.Scoped base = svc.scoped(p, scope, date);
        if (base.set().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the scope " + scope + " has no trades on " + base.date().date() + ": a what-if starts from a scope that has");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> it : asked) {
            items.add(item(p, it, base));
        }
        ReactiveHub hub = ReactiveHub.get();
        ReactiveConnector c = hub == null ? null : hub.connector(CONNECTOR).orElse(null);
        if (c == null) {
            throw new DrishtiException(ErrorCode.VAR_WHATIF_UNAVAILABLE, "the what-if runs in the Python VaR engine (the reactive connector " + CONNECTOR
                    + " of the market-risk pack, compute pool var), which is not running on this server: the compute workers are off "
                    + "(DRISHTI_COMPUTE_ENABLED=true starts them)");
        }
        Sandboxes.Sandbox box;
        try {
            box = hub.sandboxes().create(p.user(), CONNECTOR, "VaR what-if on " + scope, items);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sandbox", box.id());
            String ref = ComputeRef.format(base.res().canonical(), params);
            Answer a = c.answer(new ReactiveConnector.Ask(KIND, ref, AsOf.of(base.date().date()), Viewers.of(p), RequestClass.INTERACTIVE, WAIT));
            if (!a.ready() || a.document() == null) {
                throw new DrishtiException(a.state().equals(Answer.FAILED) ? ErrorCode.BAD_REQUEST : ErrorCode.VAR_WHATIF_UNAVAILABLE, "the Python VaR engine "
                        + (a.state().equals(Answer.COMPUTING) ? "did not answer within " + WAIT.toSeconds() + " s" : "answered " + a.state())
                        + (a.message() == null ? "" : ": " + a.message()));
            }
            Map<String, Object> out = answer(a.document(), base, scope);
            svc.audit(p, "var-whatif", base, "items=" + items.size());
            return out;
        } finally {
            hub.sandboxes().delete(p.user(), box.id());
        }
    }

    /** One hypothetical trade: a booked trade's vector (read as the person), the factor, a label. */
    private Map<String, Object> item(Principal p, Map<String, Object> it, VarService.Scoped base) {
        String from = it.get("copyOf") == null ? "" : String.valueOf(it.get("copyOf")).trim();
        if (from.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if item is a copy of a booked trade: {\"copyOf\": \"MX-30000121\", \"factor\": 1}");
        }
        double f = it.get("factor") instanceof Number n ? n.doubleValue() : it.get("factor") == null ? 1.0 : parse(it.get("factor"));
        VarService.Scoped t = svc.scoped(p, "TRD:" + from, base.date().date());
        if (t.set().isEmpty()) {
            List<String> n = new VarSelection(svc).notices(t);
            throw new DrishtiException(ErrorCode.BAD_REQUEST, n.isEmpty() ? "TRD:" + from + " has no P&L vector on " + base.date().date() : n.get(0));
        }
        List<Double> v = new ArrayList<>(t.vector().length);
        for (double x : t.vector()) {
            v.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("TRADE_ID", "WHATIF-" + from);
        m.put("LABEL", it.get("label") == null ? fmt(f) + " × " + from : String.valueOf(it.get("label")));
        m.put("PNL", v);
        m.put("FACTOR", f);
        return m;
    }

    private static double parse(Object o) {
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if factor is a number (-1: the unwind), not " + o);
        }
    }

    private static String fmt(double f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> answer(Map<String, Object> doc, VarService.Scoped base, String scope) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> change = new LinkedHashMap<>();
        for (String[] f : new String[][] {{"var", "VAR"}, {"es", "ES"}}) {
            before.put(f[0], round(doc.get("BEFORE_" + f[1])));
            after.put(f[0], round(doc.get("AFTER_" + f[1])));
            change.put(f[0], round(doc.get("CHANGE_" + f[1])));
        }
        before.put("stressedVar", round(doc.get("STRESSED_VAR_BEFORE")));
        after.put("stressedVar", round(doc.get("STRESSED_VAR_AFTER")));
        Map<String, Object> tables = doc.get("TABLES") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Map<String, Object>> items = new ArrayList<>();
        if (tables.get("items") instanceof List<?> rows) {
            for (Object o : rows) {
                Map<String, Object> r = (Map<String, Object>) o;
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("label", r.get("LABEL"));
                x.put("factor", r.get("FACTOR"));
                x.put("standaloneVar", round(r.get("STANDALONE_VAR")));
                x.put("incrementalVar", round(r.get("INCREMENTAL_VAR")));
                x.put("incrementalAlone", round(r.get("INCREMENTAL_ALONE")));
                x.put("componentVar", round(r.get("COMPONENT_VAR")));
                items.add(x);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", base.res().canonical());
        out.put("businessDate", base.date().date().toString());
        out.put("before", before);
        out.put("after", after);
        out.put("change", change);
        out.put("items", items);
        out.put("engine", "var-whatif (Python VaR engine)");
        out.put("note", "each hypothetical trade is a copy of a booked trade's P&L vector over the " + base.date().book().width()
                + " scenario days, scaled by its factor, added to the scope; incremental VaR in the order listed");
        return out;
    }

    private static Long round(Object o) {
        return o instanceof Number x && Double.isFinite(x.doubleValue()) ? Math.round(x.doubleValue()) : null;
    }
}
