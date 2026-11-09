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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.ccr.ExposureBook;
import com.ash.drishti.banking.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pre-deal check (EXPOSURE_GUIDE.md section 18.9): does a new trade with a counterparty fit its credit limit? A request
 * ({@link ExposurePreDealCommand}: typed or as fields) names the counterparty and the trade: a <b>template</b>, a booked trade the asker
 * sees whose paths on the date's run are copied and scaled to the notional (the exact method {@code paths}), or an asset class, notional
 * and maturity (the conservative proxy {@code addon}). The exposure what-if of this engine computes the counterparty's profile with the
 * trade added ({@link ExposureWhatIf}, {@link ExposureWhatIfPaths}); the check holds the peak of the limit's measure, and each tenor
 * bucket's, to the stored credit limit and answers:
 *
 * <ul>
 *   <li>{@code approve}: the exact method, the limit and every bucket within it after the trade, the whole counterparty seen, a sound run;
 *   <li>{@code decline}: the exact method takes the limit or a bucket over;
 *   <li>{@code refer}: anything less than exact data: a proxy answer (it only refers, never approves), no limit on record, a limit set on
 *       a measure the engine does not compute, netting sets the asker does not see, the date's run stale, the netting set not certain, or
 *       no exposure for the counterparty on the date. Each reason is said.
 * </ul>
 *
 * Every answer shows the method and the template, and is audited ({@code ccr-predeal}, with its check id and the decision). The limit and
 * each bucket say {@code within}, {@code near} (from {@code drishti.risk.limit-near} of the limit, 0.8, {@link com.ash.drishti.banking.RiskLimits}) or
 * {@code breach}. With EEPE in the answer (the exact method) the exposure also carries the IMM EAD, alpha x EEPE, before, with and
 * incremental. SA-CCR's incremental EAD is release 2.9.11.k: the answer says it is under construction.
 */
final class ExposurePreDeal {

    static final String APPROVE = "approve";
    static final String REFER = "refer";
    static final String DECLINE = "decline";
    private static final Pattern PCT = Pattern.compile("(\\d{2}(?:\\.\\d+)?)");
    private final ExposureService svc;
    private final ExposureWhatIf whatIf;

    private final com.ash.drishti.banking.RiskLimits limits;

    ExposurePreDeal(ExposureService svc, ExposureWhatIf whatIf, com.ash.drishti.banking.RiskLimits limits) {
        this.svc = svc;
        this.limits = limits == null ? com.ash.drishti.banking.RiskLimits.defaults() : limits;
        this.whatIf = whatIf;
    }

    /** The measure a limit is set on, and its confidence for a PFE. */
    record Measure(String figure, double q, String text) {

        /** {@code PFE 95 peak}, {@code peak PFE}, {@code EEPE}, {@code EPE}, {@code EE peak}; null: one the engine does not compute. */
        static Measure of(String stored, double defaultQ) {
            String m = stored == null || stored.isBlank() ? "PFE" : stored.trim().toUpperCase(Locale.ROOT);
            if (m.contains("PFE")) {
                Matcher x = PCT.matcher(m);
                double q = x.find() ? Double.parseDouble(x.group(1)) / 100 : defaultQ;
                return q > 0.5 && q < 1 ? new Measure("peakPfe", q, stored == null ? "peak PFE" : stored.trim()) : null;
            }
            if (m.contains("EEPE")) {
                return new Measure("eepe", defaultQ, stored.trim());
            }
            if (m.matches(".*\\bEPE\\b.*")) {
                return new Measure("epe", defaultQ, stored.trim());
            }
            if (m.matches(".*\\bEE\\b.*")) {
                return new Measure("peakEe", defaultQ, stored.trim());
            }
            return null;
        }

        boolean profiled() {
            return figure.equals("peakPfe") || figure.equals("peakEe");
        }

        String column() {
            return figure.equals("peakPfe") ? "pfe" : "ee";
        }
    }

    /** One check, as the asker (audited). */
    Map<String, Object> check(Principal p, Map<String, Object> body, LocalDate date) {
        return check(p, body, date, true);
    }

    /**
     * One check, as the asker; {@code audit} false only for the one Explain, which shows how a check the screen made was computed (the
     * same request, the same numbers: the check itself was audited when it was made).
     */
    Map<String, Object> check(Principal p, Map<String, Object> body, LocalDate date, boolean audit) {
        ExposurePreDealCommand cmd = ExposurePreDealCommand.of(body);
        String checkId = "PDC-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(Locale.ROOT);
        String scope = "CPTY:" + cmd.counterparty();
        ExposureService.Ask plain = ExposureService.Ask.of(svc.props(), null, null, null, null);
        ExposureService.Scoped first;
        try {
            first = svc.scoped(p, scope, date, plain);
        } catch (DrishtiException e) {
            if (e.errorCode() != ErrorCode.ENTITY_NOT_FOUND) {
                throw e;
            }
            Map<String, Object> out = head(checkId, cmd, null);
            List<String> why = List.of("no exposure for " + cmd.counterparty() + " to add the trade to (" + strip(e.getMessage())
                    + "): a new counterparty, or one the run does not cover; credit decides");
            out.put("decision", REFER);
            out.put("reason", why.get(0));
            out.put("reasons", why);
            out.put("method", method(cmd.exact() ? "paths" : "addon", null));
            out.put("template", cmd.exact() ? Map.of("tradeId", cmd.like()) : proxyTemplate(cmd, null));
            out.put("saCcr", saCcr());
            if (audit) {
                svc.audit(p == null ? ExposureOfficialStore.OWNER : p.user(), "ccr-predeal", scope, "check=" + checkId + " decision=refer command=\""
                        + cmd.text() + "\" reason=no-exposure");
            }
            return out;
        }
        ExposureDate d = first.date();
        ExposureBook b = d.book();
        String cp = first.res().id() == null ? cmd.counterparty() : first.res().id();
        ExposureDate.Limit limit = d.limits().get(cp);
        Measure measure = limit == null ? null : Measure.of(limit.measure(), svc.props().limitConfidence());
        double q = measure == null ? svc.props().limitConfidence() : measure.q();
        ExposureService.Ask ask = ExposureService.Ask.of(svc.props(), String.valueOf(q), null, null, null);
        ExposureService.Scoped base = svc.scoped(p, scope, d.date(), ask);
        if (base.result().pieces.isEmpty() && base.hiddenSets() > 0) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "you see none of " + cp + "'s netting sets: a pre-deal check is made by who sees the "
                    + "counterparty");
        }
        List<String> reasons = new ArrayList<>();

        // the netting sets of the counterparty (seen whole), and the one the trade goes into
        List<String> sets = new ArrayList<>();
        for (ExposureCalc.Piece pc : base.result().pieces) {
            if (pc.whole) {
                sets.add(b.set(pc.set).id());
            }
        }
        Map<String, Object> template;
        Map<String, Object> cand = new LinkedHashMap<>();
        cand.put("tradeId", "CANDIDATE-1");
        String set = cmd.nettingSet();
        String setHow = set == null ? null : "named";
        String method;
        if (cmd.exact()) {
            int t = b.tradeOrdinal(cmd.like());
            if (t < 0) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no trade " + cmd.like() + " on the run " + b.runId() + " of " + d.date()
                        + " to copy: a template is a booked trade with exposure paths");
            }
            if (!base.view().seesTrades(OrdinalSet.of(t))) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "the trade " + cmd.like() + " is not one you see trade by trade: a template is a trade you see");
            }
            ExposureBook.TradeInfo ti = b.trade(t);
            double tn = Math.abs(ti.notional());
            double notional = cmd.notional() == null ? tn : cmd.notional();
            if (!(tn > 0) && cmd.notional() != null) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the template " + ti.id() + " has no notional to scale from: leave the "
                        + "notional out (a copy as it is)");
            }
            double factor = (cmd.reverse() ? -1 : 1) * (tn > 0 ? notional / tn : 1);
            String own = b.set(ti.set()).id();
            if (set == null && cp.equals(b.set(ti.set()).counterparty())) {
                set = own;
                setHow = "the template's";
            }
            template = new LinkedHashMap<>();
            template.put("tradeId", ti.id());
            template.put("product", ti.product());
            template.put("assetClass", ti.assetClass());
            template.put("notional", tn);
            template.put("maturity", ti.maturity());
            template.put("counterparty", ti.counterparty());
            template.put("nettingSet", own);
            template.put("mtm", Math.round(ti.mtm0()));
            template.put("factor", factor);
            template.put("side", cmd.reverse() ? "reverse" : "same");
            template.put("candidateNotional", notional);
            template.put("candidateMtm", Math.round(cmd.mtm() != null ? cmd.mtm() : factor * ti.mtm0()));
            cand.put("label", ti.product() + " like " + ti.id() + " × " + ExposureViews.round4(factor));
            cand.put("paths", Map.of("copyOf", ti.id(), "factor", factor));
            if (cmd.mtm() != null) {
                cand.put("mtm", cmd.mtm());
            }
            method = "paths";
        } else {
            LocalDate mat = cmd.maturityOn(d.date());
            if (!mat.isAfter(d.date())) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the maturity " + mat + " is not after the business date " + d.date());
            }
            template = proxyTemplate(cmd, mat);
            cand.put("label", cmd.assetClass() + " " + ExposurePreDealCommand.amount(cmd.notional()) + " to " + mat);
            cand.put("assetClass", cmd.assetClass());
            cand.put("notional", cmd.notional());
            cand.put("maturityDate", mat.toString());
            cand.put("mtm", cmd.mtm() == null ? 0.0 : cmd.mtm());
            cand.put("delta", cmd.reverse() ? -1.0 : 1.0);
            method = "addon";
            reasons.add("a proxy answer (method addon: the trade a netting set of its own, uncollateralised, with SA-CCR's supervisory factor) "
                    + "only refers, never approves: give a template (LIKE <trade>) for the exact answer");
        }
        if (set != null) {
            if (!sets.contains(set)) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the netting set " + set + " is not one of " + cp + "'s that you see: "
                        + (sets.isEmpty() ? "none" : String.join(", ", sets)));
            }
        } else if (sets.size() == 1) {
            set = sets.get(0);
            setHow = "the counterparty's only";
        } else if (method.equals("paths")) {
            setHow = "its own";
            reasons.add(cp + " has " + sets.size() + " netting sets (" + String.join(", ", sets) + "): the trade is shown in a netting set of its own, "
                    + "uncollateralised; name the one it goes into (IN NS-…)");
        }
        if (method.equals("paths") && set != null) {
            cand.put("nettingSet", set);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("scope", scope);
        req.put("method", method);
        req.put("q", q);
        req.put("candidates", List.of(cand));
        Map<String, Object> w = whatIf.whatIf(p, req, d.date(), false);

        // the decision
        if (limit == null) {
            reasons.add("no credit limit on record for " + cp + " (credit-limit): credit decides");
        } else if (measure == null) {
            reasons.add("the limit " + limit.id() + " is set on " + limit.measure() + ", which the exposure engine does not compute: credit decides");
        } else if (!(w.get("with") instanceof Map<?, ?> wm && wm.get(measure.figure()) instanceof Number)) {
            reasons.add("the " + method + " method does not compute " + measure.text() + ", the measure of the limit " + limit.id() + ": credit decides");
            measure = null;
        }
        if (base.hiddenSets() > 0 || base.hiddenTrades() > 0) {
            reasons.add("you do not see all of " + cp + "'s netting sets (" + base.hiddenSets() + " left out): credit, who sees them all, decides");
        }
        List<String> flags = base.result().flags == null ? List.of() : List.copyOf(base.result().flags);
        if (flags.contains("stale-run")) {
            reasons.add("the date's run is stale (" + String.join("; ", d.warnings()) + "): the check is not made on it");
        }
        double near = limits.limitNear();
        Map<String, Object> lim = limit == null || measure == null ? null : limitUse(limit, measure, w, near);
        String decision;
        if (!reasons.isEmpty()) {
            decision = REFER;
        } else if (lim != null && "breach".equals(lim.get("status"))) {
            decision = DECLINE;
            reasons.add(lim.get("breachText").toString());
        } else {
            decision = APPROVE;
            reasons.add("exact: with the trade, " + measure.text() + " is " + Math.round(num(lim.get("after"))) + " of the limit "
                    + Math.round(limit.amount()) + " (" + pct(num(lim.get("useAfter"))) + ")" + (limit.buckets().isEmpty() ? "" : ", every tenor bucket within its limit")
                    + ("near".equals(lim.get("status")) ? "; near the limit (" + pct(near) + " or more of it" + (lim.get("nearText") == null ? ""
                    : ": " + lim.get("nearText")) + ")" : ""));
        }
        if (lim != null) {
            lim.remove("breachText");
            lim.remove("nearText");
        }
        Map<String, Object> out = head(checkId, cmd, base);
        out.put("decision", decision);
        out.put("reason", reasons.get(0));
        out.put("reasons", reasons);
        out.put("method", method(method, w));
        out.put("template", template);
        Map<String, Object> ns = new LinkedHashMap<>();
        ns.put("id", set);
        ns.put("how", setHow);
        ns.put("sets", sets);
        out.put("nettingSet", ns);
        out.put("limit", lim);
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("base", w.get("base"));
        exp.put("with", w.get("with"));
        exp.put("incremental", w.get("incremental"));
        ead(exp, svc.props().alpha());
        out.put("exposure", exp);
        out.put("saCcr", saCcr());
        List<Object> fl = new ArrayList<>(flags);
        if (w.get("flags") instanceof List<?> wf) {
            fl.addAll(wf);
        }
        out.put("flags", fl);
        out.put("engine", "exposure (Java)");
        if (audit) {
            svc.audit(p, "ccr-predeal", base, "check=" + checkId + " decision=" + decision + " method=" + method + " command=\"" + cmd.text() + "\""
                + (lim == null ? " limit=none" : " limit=" + limit.id() + " after=" + Math.round(num(lim.get("after"))) + " use=" + pct(num(lim.get("useAfter")))));
        }
        return out;
    }

    private Map<String, Object> head(String checkId, ExposurePreDealCommand cmd, ExposureService.Scoped base) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkId", checkId);
        out.put("command", cmd.text());
        out.put("counterparty", cmd.counterparty());
        if (base != null) {
            out.put("scope", base.res().canonical());
            out.put("label", base.label());
            out.put("businessDate", base.date().date().toString());
            out.put("runId", base.date().book().runId());
            out.put("confidence", base.ask().q());
        }
        out.put("audited", true);
        return out;
    }

    private static Map<String, Object> proxyTemplate(ExposurePreDealCommand cmd, LocalDate mat) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tradeId", null);
        t.put("assetClass", cmd.assetClass());
        t.put("candidateNotional", cmd.notional());
        t.put("maturity", mat == null ? cmd.maturity() : mat.toString());
        t.put("candidateMtm", cmd.mtm() == null ? 0 : cmd.mtm());
        t.put("side", cmd.reverse() ? "reverse" : "same");
        t.put("note", "described, not copied: no booked trade's paths, so the proxy");
        return t;
    }

    private static Map<String, Object> method(String id, Map<String, Object> w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("exact", id.equals("paths"));
        m.put("name", id.equals("paths") ? "exact: the template's paths on the run added to the netting set" : "proxy: an SA-CCR-style add-on");
        m.put("text", w != null && w.get("note") != null ? w.get("note") : id.equals("paths")
                ? "the template's paths on the date's run, scaled, added to the netting set path by path; collateral and exposure computed again"
                : "the trade a netting set of its own, uncollateralised: max(MTM, 0) + z_q SF d delta sqrt(min(t, M)) at each date");
        return m;
    }

    /**
     * The IMM EAD (alpha x EEPE) before, with the trade and incremental, where the answer has EEPE (the exact method's); the proxy has none
     * and says so by leaving it out.
     */
    @SuppressWarnings("unchecked")
    static void ead(Map<String, Object> exp, double alpha) {
        if (!(exp.get("base") instanceof Map<?, ?> b && b.get("eepe") instanceof Number b0 && exp.get("with") instanceof Map<?, ?> wi
                && wi.get("eepe") instanceof Number w0)) {
            return;
        }
        long before = Math.round(alpha * b0.doubleValue());
        long after = Math.round(alpha * w0.doubleValue());
        Map<String, Object> base = new LinkedHashMap<>((Map<String, Object>) b);
        Map<String, Object> with = new LinkedHashMap<>((Map<String, Object>) wi);
        base.put("ead", before);
        with.put("ead", after);
        exp.put("base", base);
        exp.put("with", with);
        if (exp.get("incremental") instanceof Map<?, ?> in) {
            Map<String, Object> inc = new LinkedHashMap<>((Map<String, Object>) in);
            inc.put("ead", after - before);
            exp.put("incremental", inc);
        }
        exp.put("alpha", alpha);
    }

    /** {@code breach} over 100%, {@code near} from the near share, else {@code within}: the module's one rule (RiskLimits). */
    static String status(double use, double near) {
        return new com.ash.drishti.banking.RiskLimits(near).status(use);
    }

    /**
     * The limit's use before and after, and each tenor bucket's (the profile's peak within the bucket against the bucket's limit), each
     * {@code within}, {@code near} (from {@code near} of its limit) or {@code breach}.
     */
    static Map<String, Object> limitUse(ExposureDate.Limit limit, Measure m, Map<String, Object> w, double near) {
        Map<?, ?> base = (Map<?, ?>) w.get("base");
        Map<?, ?> with = (Map<?, ?>) w.get("with");
        double before = num(base.get(m.figure()));
        double after = num(with.get(m.figure()));
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("id", limit.id());
        l.put("measure", m.text());
        l.put("confidence", m.figure().equals("peakPfe") ? m.q() : null);
        l.put("limit", Math.round(limit.amount()));
        l.put("before", Math.round(before));
        l.put("after", Math.round(after));
        l.put("increment", Math.round(after - before));
        l.put("useBefore", before / limit.amount());
        l.put("useAfter", after / limit.amount());
        l.put("headroomBefore", Math.round(limit.amount() - before));
        l.put("headroomAfter", Math.round(limit.amount() - after));
        l.put("near", near);
        boolean breach = after > limit.amount();
        boolean nearAny = !breach && after / limit.amount() >= near;
        String nearText = null;
        String breachText = breach ? "exact: with the trade, " + m.text() + " is " + Math.round(after) + ", over the limit " + Math.round(limit.amount())
                + " (" + pct(after / limit.amount()) + ")" : null;
        List<Map<String, Object>> buckets = new ArrayList<>();
        if (m.profiled() && !limit.buckets().isEmpty() && w.get("incremental") instanceof Map<?, ?> inc && inc.get("profile") instanceof List<?> prof) {
            String col = m.column();
            String colBase = col + "Base";
            for (ExposureDate.Bucket bk : limit.buckets()) {
                double b0 = 0;
                double b1 = 0;
                int points = 0;
                for (Object o : prof) {
                    Map<?, ?> r = (Map<?, ?>) o;
                    if (bk.holds(num(r.get("t")))) {
                        b0 = Math.max(b0, num(r.get(colBase)));
                        b1 = Math.max(b1, num(r.get(col)));
                        points++;
                    }
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("bucket", bk.name());
                x.put("limit", Math.round(bk.limit()));
                x.put("before", points == 0 ? null : Math.round(b0));
                x.put("after", points == 0 ? null : Math.round(b1));
                x.put("increment", points == 0 ? null : Math.round(b1 - b0));
                x.put("useBefore", points == 0 ? null : b0 / bk.limit());
                x.put("useAfter", points == 0 ? null : b1 / bk.limit());
                x.put("headroomAfter", points == 0 ? null : Math.round(bk.limit() - b1));
                boolean over = points > 0 && b1 > bk.limit();
                String st = points == 0 ? "no-dates" : status(b1 / bk.limit(), near);
                x.put("status", st);
                if (st.equals("near") && nearText == null) {
                    nearText = "the " + bk.name() + " bucket at " + pct(b1 / bk.limit()) + " of its limit";
                }
                nearAny |= st.equals("near");
                if (over && breachText == null) {
                    breachText = "exact: with the trade, the " + bk.name() + " bucket's peak is " + Math.round(b1) + ", over its limit " + Math.round(bk.limit());
                }
                breach |= over;
                buckets.add(x);
            }
        }
        l.put("buckets", buckets);
        l.put("status", breach ? "breach" : nearAny ? "near" : "within");
        l.put("breachText", breachText);
        l.put("nearText", nearText);
        return l;
    }

    private static Map<String, Object> saCcr() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", "under-construction");
        s.put("release", "2.9.11.k");
        s.put("note", "SA-CCR's incremental EAD in the pre-deal answer is built with the SA-CCR engine (2.9.11.k): Under construction");
        return s;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static String pct(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.1f%%", v * 100) : "n/a";
    }

    private static String strip(String m) {
        return String.valueOf(m).replaceFirst("^DRS-\\d{4} ", "");
    }
}
