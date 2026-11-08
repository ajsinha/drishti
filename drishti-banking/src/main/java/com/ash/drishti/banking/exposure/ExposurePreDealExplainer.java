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
package com.ash.drishti.banking.exposure;

import com.ash.drishti.server.explain.ExplainRequest;
import com.ash.drishti.server.explain.Explainer;
import com.ash.drishti.server.explain.Explanation;
import com.ash.drishti.server.security.Principal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Explain a pre-deal check (kind {@code predeal}; EXPOSURE_GUIDE.md section 18.9), the one Explain of the Counterparties → Pre-deal check
 * screen: the decision and its reasons, the limit's use before and after with the formula and its values, the headroom, each tenor
 * bucket, the near share, the EAD, the method and the template, and the run. The check is made again as the person asks (the same
 * request, so the same numbers; not audited a second time, the check itself was).
 *
 * <p>Arguments: {@code command} ({@code PREDEAL CP-ALDERSHOT LIKE 1000001 250M}, as the check wrote it back) or the check's fields
 * ({@code counterparty}, {@code like}, {@code notional}, {@code side}, {@code nettingSet}, {@code assetClass}, {@code maturity},
 * {@code mtm}), and {@code date}.
 */
final class ExposurePreDealExplainer implements Explainer {

    static final List<String> MEASURES = List.of("decision", "headroom", "increment", "ead");

    private final ExposurePreDeal preDeal;

    ExposurePreDealExplainer(ExposurePreDeal preDeal) {
        this.preDeal = preDeal;
    }

    @Override
    public String kind() {
        return "predeal";
    }

    @Override
    public String title() {
        return "A pre-deal check: the decision, the limit's use and headroom, the incremental exposure";
    }

    @Override
    public List<String> measures() {
        return MEASURES;
    }

    @Override
    public String engine() {
        return "exposure";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Explanation explain(Principal p, ExplainRequest r) {
        Map<String, Object> a = preDeal.check(p, new java.util.LinkedHashMap<>(r.args()), r.date("date"), false);
        String measure = r.measureOr("decision");
        Map<String, Object> lim = a.get("limit") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Map<String, Object> exp = a.get("exposure") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> base = map(exp.get("base"));
        Map<String, Object> with = map(exp.get("with"));
        Map<String, Object> method = map(a.get("method"));
        Map<String, Object> set = map(a.get("nettingSet"));
        String decision = String.valueOf(a.get("decision"));
        String cp = String.valueOf(a.get("counterparty"));
        Explanation x = new Explanation("predeal", measure, "CPTY:" + cp, title(measure) + ": " + a.get("command"));
        Double value = switch (measure) {
            case "headroom" -> lim == null ? null : dbl(lim.get("headroomAfter"));
            case "increment" -> lim == null ? dbl(map(exp.get("incremental")).get("peakPfe")) : dbl(lim.get("increment"));
            case "ead" -> dbl(with.get("ead"));
            default -> lim == null ? null : dbl(lim.get("after"));
        };
        x.value(value, "USD", a.get("businessDate") == null ? null : String.valueOf(a.get("businessDate")));
        x.origin("computed by the exposure engine in the server for you (" + a.getOrDefault("engine", "exposure (Java)") + "), the check made again "
                + "as you ask (the check itself was audited when it was made)");
        x.preview(decision.toUpperCase(Locale.ROOT) + ": " + a.get("reason"), "method " + method.get("id"),
                lim == null ? "no limit applied" : "limit " + lim.get("id") + " · " + lim.get("status"));
        if (lim != null) {
            double l = num(lim.get("limit"));
            double before = num(lim.get("before"));
            double after = num(lim.get("after"));
            String what = String.valueOf(lim.get("measure"));
            x.formula("measure of the limit " + lim.get("id") + ": " + what + (lim.get("confidence") == null ? "" : " at " + pct(num(lim.get("confidence")))));
            x.formula("before = " + Explanation.number(before) + "   with the trade = " + Explanation.number(after) + "   increment = with − before = "
                    + Explanation.signed(after - before));
            x.formula("use = with ÷ limit = " + Explanation.number(after) + " ÷ " + Explanation.number(l) + " = " + pct(num(lim.get("useAfter")))
                    + "   (before: " + pct(num(lim.get("useBefore"))) + ")");
            x.formula("headroom = limit − with = " + Explanation.number(l) + " − " + Explanation.number(after) + " = " + Explanation.signed(l - after));
            x.formula("status: breach over 100% of the limit, near from " + pct(num(lim.get("near"))) + " (limit-near), else within → "
                    + lim.get("status"));
            if (lim.get("buckets") instanceof List<?> bs) {
                for (Object o : bs) {
                    Map<String, Object> b = map(o);
                    x.formula("bucket " + b.get("bucket") + ": the profile's peak in the bucket " + text(b.get("before")) + " → " + text(b.get("after"))
                            + " of " + Explanation.number(num(b.get("limit"))) + " (" + (b.get("useAfter") == null ? "no dates" : pct(num(b.get("useAfter"))))
                            + ") → " + b.get("status"));
                }
            }
        }
        if (with.get("ead") instanceof Number) {
            x.formula("EAD (IMM) = α × EEPE = " + exp.get("alpha") + " × " + Explanation.number(num(with.get("eepe"))) + " = "
                    + Explanation.number(num(with.get("ead"))) + "   (before " + Explanation.number(num(base.get("ead"))) + ")");
        }
        x.formula("decision: " + decision + " (refer on anything less than exact data; decline when the limit or a bucket is over; else approve)");
        if (r.wants("inputs")) {
            Map<String, Object> t = map(a.get("template"));
            x.input("Command", a.get("command"));
            x.input("Counterparty", cp);
            x.input("Netting set", set.get("id") == null ? "a netting set of its own (" + set.get("how") + ")" : set.get("id") + " (" + set.get("how") + ")");
            x.input("Method", method.get("name"));
            x.input("How", method.get("text"));
            x.input("Template", t.get("tradeId") == null ? "described: " + t.get("assetClass") + " to " + t.get("maturity")
                    : t.get("tradeId") + " · " + t.get("product") + " · factor " + t.get("factor") + " (" + t.get("side") + ")");
            x.input("Confidence", a.get("confidence") instanceof Number q ? pct(q.doubleValue()) : "");
            x.input("Flags", a.get("flags") instanceof List<?> f && !f.isEmpty() ? String.join(", ", f.stream().map(String::valueOf).toList()) : "none");
            x.input("Check", a.get("checkId") + " (this explanation's own; not audited)");
            x.lineage("exposure run", String.valueOf(a.get("runId")), String.valueOf(a.get("businessDate")));
            if (lim != null) {
                x.lineage("credit-limit", String.valueOf(lim.get("id")), String.valueOf(a.get("businessDate")));
            }
        }
        if (a.get("reasons") instanceof List<?> rs) {
            for (Object o : rs) {
                x.check(decision.equals(ExposurePreDeal.APPROVE) ? Boolean.TRUE : decision.equals(ExposurePreDeal.DECLINE) ? Boolean.FALSE : null,
                        String.valueOf(o));
            }
        }
        x.whyUnavailable("a pre-deal check is of one moment and one trade: there is no previous date to compare it with");
        x.unbuilt("workbook", "explain-exposure-workbook");
        x.link("The pre-deal check", "/predeal?command=" + URLEncoder.encode(String.valueOf(a.get("command")), StandardCharsets.UTF_8));
        x.link("The counterparty's exposure", "/exposure?scope=" + URLEncoder.encode("CPTY:" + cp, StandardCharsets.UTF_8));
        x.link("Guide: the pre-deal check", "/help/context/exposure");
        return x;
    }

    private static String title(String measure) {
        return switch (measure) {
            case "headroom" -> "Headroom after the trade";
            case "increment" -> "Incremental exposure";
            case "ead" -> "EAD (IMM) with the trade";
            default -> "Pre-deal decision";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static String text(Object o) {
        return o instanceof Number n ? Explanation.number(n.doubleValue()) : "—";
    }

    private static String pct(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.1f%%", v * 100) : "n/a";
    }
}
