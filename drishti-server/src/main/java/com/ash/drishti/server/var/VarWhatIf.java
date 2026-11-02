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
import com.ash.drishti.compute.var.VarWhatIfMath;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-deal what-if (VAR_GUIDE.md section 8.5): hypothetical trades, each a copy of a booked trade's P&amp;L vector scaled by a factor
 * ({@code -1}: its unwind), added to a scope; the scope's VaR, ES and stressed window before and after, and each trade's standalone,
 * incremental and component VaR. Computed here, in the Java VaR engine that answers every scope ({@link VarWhatIfMath}, the same rules as
 * every other VaR answer): the scope and the trades' vectors are read as the person (a trade they may not see one by one is refused); no
 * sandbox, no compute workers, so it answers on every server.
 *
 * <pre>
 *   POST /api/v1/var/what-if {"scope": "BASKET:asha/unwind", "items": [{"copyOf": "MX-30000121", "factor": -1}], "ccy": "EUR"}
 *   POST /api/v1/var/what-if {"scope": "DESK:RATES", "items": [{"copyOf": "BOOK:BOOK-RATES-3", "factor": -0.5, "label": "hedge"}]}
 * </pre>
 */
final class VarWhatIf {

    static final String ENGINE = "Java VaR engine (the server)";
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
        String ccy = VarCurrencies.normalise(body.get("ccy") == null ? null : String.valueOf(body.get("ccy")));
        VarService.Scoped base = svc.scoped(p, scope, date, ccy);
        if (base.set().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the scope " + scope + " has no trades on " + base.date().date() + ": a what-if starts from a scope that has");
        }
        List<VarWhatIfMath.Item> items = new ArrayList<>();
        for (Map<String, Object> it : asked) {
            items.add(item(p, it, base));
        }
        VarWhatIfMath.Result r;
        try {
            r = VarWhatIfMath.of(base.vector(), items, svc.calc(), VarWhatIfMath.STRESS_WINDOW);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        Map<String, Object> out = answer(r, base, scope);
        svc.audit(p, "var-whatif", base, "items=" + items.size());
        return out;
    }

    /**
     * One hypothetical trade times its factor, with its label: a copy of a booked trade's vector ({@code copyOf}: a trade id, or any
     * scope such as {@code BOOK:BOOK-RATES-3} for a hedge shaped like a book; read as the person), or a vector priced elsewhere
     * ({@code pnl}: one P&amp;L per scenario day, in the answer's reporting currency).
     */
    private VarWhatIfMath.Item item(Principal p, Map<String, Object> it, VarService.Scoped base) {
        String from = it.get("copyOf") == null ? "" : String.valueOf(it.get("copyOf")).trim();
        boolean priced = it.get("pnl") instanceof List<?>;
        if (from.isEmpty() && !priced) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if item is a copy of a booked trade, {\"copyOf\": \"MX-30000121\", \"factor\": 1}, "
                    + "or a P&L priced elsewhere, {\"label\": \"new swap\", \"pnl\": [one per scenario day], \"factor\": 1}");
        }
        double f = it.get("factor") instanceof Number n ? n.doubleValue() : it.get("factor") == null ? 1.0 : parse(it.get("factor"));
        if (!Double.isFinite(f)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if factor is a finite number (-1: the unwind), not " + it.get("factor"));
        }
        double[] v;
        if (priced) {
            List<?> l = (List<?>) it.get("pnl");
            v = new double[l.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = l.get(i) instanceof Number n ? n.doubleValue() : Double.NaN;
                if (!Double.isFinite(v[i])) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "what-if item " + (it.get("label") == null ? "" : it.get("label") + " ")
                            + "has a P&L that is not a number on scenario day " + (i + 1));
                }
            }
            from = it.get("label") == null ? "a trade priced elsewhere" : String.valueOf(it.get("label"));
        } else {
            String text = from.contains(":") ? from : "TRD:" + from;
            VarService.Scoped t = svc.scoped(p, text, base.date().date(), base.date().currency());
            if (t.set().isEmpty()) {
                List<String> n = new VarSelection(svc).notices(t);
                throw new DrishtiException(ErrorCode.BAD_REQUEST, n.isEmpty() ? text + " has no P&L vector on " + base.date().date() : n.get(0));
            }
            v = t.vector().clone();
        }
        for (int i = 0; i < v.length; i++) {
            v[i] *= f;
        }
        String label = it.get("label") == null ? fmt(f) + " × " + from : String.valueOf(it.get("label"));
        return new VarWhatIfMath.Item(label, f, v);
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

    private Map<String, Object> answer(VarWhatIfMath.Result r, VarService.Scoped base, String scope) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> change = new LinkedHashMap<>();
        before.put("var", round(r.beforeVar()));
        after.put("var", round(r.afterVar()));
        change.put("var", round(r.changeVar()));
        before.put("es", round(r.beforeEs()));
        after.put("es", round(r.afterEs()));
        change.put("es", round(r.changeEs()));
        before.put("stressedVar", round(r.stressedBefore()));
        after.put("stressedVar", round(r.stressedAfter()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (VarWhatIfMath.ItemResult it : r.items()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("label", it.label());
            x.put("factor", it.factor());
            x.put("standaloneVar", round(it.standaloneVar()));
            x.put("incrementalVar", round(it.incrementalVar()));
            x.put("incrementalAlone", round(it.incrementalAlone()));
            x.put("componentVar", round(it.componentVar()));
            items.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", base.res().canonical());
        out.put("businessDate", base.date().date().toString());
        out.put("currency", base.date().currency());
        out.put("confidence", svc.calc().confidence());                // the labels the console shows (never hard-coded)
        out.put("esConfidence", svc.calc().esConfidence());
        out.put("before", before);
        out.put("after", after);
        out.put("change", change);
        out.put("items", items);
        out.put("engine", ENGINE);
        out.put("note", "each hypothetical trade is a copy of a booked trade's P&L vector over the " + base.date().book().width()
                + " scenario days, scaled by its factor, added to the scope; incremental VaR in the order listed; the stressed VaR is the "
                + "largest VaR over any " + Math.min(r.stressWindow(), base.vector().length) + " consecutive scenario days");
        return out;
    }

    private static Long round(double x) {
        return Double.isFinite(x) ? Math.round(x) : null;
    }
}
