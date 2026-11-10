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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarMath;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.banking.var.VarExplainMath.Op;
import com.ash.drishti.banking.var.VarExplainMath.State;
import com.ash.drishti.banking.var.VarExplainMath.Step;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Why VaR moved (VAR_GUIDE.md section 3.11; docs/architecture/LENSES.md section 4.1, RO1): a scope's VaR on two business dates (default: the
 * business date before the newest, and the newest), split into causes that add up exactly to the change, with who drove each.
 *
 * <p>The two dates are opened through the engine ({@link VarService#date}, so both are counted in {@code hot-dates} and the least
 * recently used leaves first). The earlier date starts from its official capture when the scope is an official node the viewer sees whole
 * (S0, its vector as captured); otherwise from its live figure. The scope is resolved as the viewer on each date; the trades are told
 * apart by id: <b>out</b> (in the scope on the earlier date, no vector or cancelled on the later), <b>in</b> (in the scope on the later
 * date, no vector on the earlier), <b>moved</b> by the hierarchy (on both dates, in the scope on one only) and <b>amended</b> (when the
 * feed marks it). Only their vectors are summed; the scope's two totals are the engine's own. The arithmetic is {@link VarExplainMath}.
 *
 * <p>Never stored: answers are kept for {@code cache-minutes}, weighed in bytes, keyed by both dates' data versions and the viewer.
 */
final class VarExplain {

    /** The words that mark an amended trade in {@code TRADE_ACTION} or {@code TRADE_STATUS}. */
    static final Set<String> AMENDED = Set.of("AMEND", "AMENDED", "AMENDMENT");
    private static final double CENT = 0.005;

    private final VarService svc;
    private final VarExplainProperties props;
    private final com.github.benmanes.caffeine.cache.Cache<String, Result> cache;

    /** An answer: the JSON, and what the workbook needs (the inputs and the states). */
    record Result(Map<String, Object> answer, VarExplainMath.Inputs inputs, VarExplainMath.Result math, VarCalc calc, String label, String canonical,
            LocalDate from, LocalDate to) {}

    VarExplain(VarService svc, VarExplainProperties props) {
        this.svc = svc;
        this.props = props;
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight((long) props.cacheMb() << 20)
                .weigher(VarExplain::weight).expireAfterWrite(java.time.Duration.ofMinutes(Math.max(1, props.cacheMinutes()))).build();
    }

    VarExplainProperties props() {
        return props;
    }

    /** An answer's weight in bytes: its states' vectors and inputs, and its JSON at about 300 bytes a row. */
    static int weight(String key, Result r) {
        long b = 4096L + 2L * key.length();
        if (r.math() != null) {
            b += (long) r.math().states().size() * 8L * r.math().days().size() * 2;
        }
        if (r.inputs() != null) {
            b += 13L * 8L * r.math().days().size();
        }
        b += 300L * rows(r.answer());
        return (int) Math.min(Integer.MAX_VALUE, b);
    }

    private static long rows(Object o) {
        if (o instanceof Map<?, ?> m) {
            long n = 1;
            for (Object v : m.values()) {
                n += rows(v);
            }
            return n;
        }
        if (o instanceof List<?> l) {
            long n = 0;
            for (Object v : l) {
                n += rows(v);
            }
            return n;
        }
        return 0;
    }

    private void enabled() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the VaR change explanation is switched off on this server (drishti.var.explain.enabled)");
        }
    }

    /** The calculator at {@code confidence} (null: the engine's). */
    VarCalc calc(Double confidence) {
        VarCalc base = svc.calc();
        if (confidence == null) {
            return base;
        }
        double c = confidence > 1 ? confidence / 100.0 : confidence;
        if (svc.props().confidences().stream().noneMatch(x -> Math.abs(x - c) < 1e-9)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "confidence " + confidence + " is not offered; choose one of " + svc.props().confidences());
        }
        return new VarCalc(c, base.esConfidence(), base.rule(), base.window(), base.scaleToVar());
    }

    /** The explanation of a scope between two dates (null: the business date before the newest, and the newest), with its drivers. */
    Result explain(Principal p, String scope, LocalDate from, LocalDate to, Double confidence) {
        return explain(p, scope, from, to, confidence, true);
    }

    Result explain(Principal p, String scope, LocalDate from, LocalDate to, Double confidence, boolean drivers) {
        enabled();
        long t0 = System.nanoTime();
        VarCalc calc = calc(confidence);
        LocalDate toAsked = to == null ? svc.loader().latest() : to;
        if (toAsked == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no business date with P&L vectors is loaded: nothing to explain");
        }
        VarService.Scoped sTo = svc.scoped(p, scope, toAsked);
        LocalDate toDay = sTo.date().date();
        LocalDate fromAsked = from == null ? svc.loader().previous(toDay) : from;
        if (fromAsked == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no business date with P&L vectors before " + toDay + ": nothing to compare with");
        }
        if (!fromAsked.isBefore(toDay)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "from (" + fromAsked + ") must be before to (" + toDay + ")");
        }
        VarService.Scoped sFrom = svc.scoped(p, scope, fromAsked);
        LocalDate fromDay = sFrom.date().date();
        if (!fromDay.isBefore(toDay)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the business date before " + toDay + " that has vectors is not before it: " + fromDay);
        }
        String key = String.join("|", sFrom.date().date().toString(), sFrom.date().version(), "i" + sFrom.date().intradayStamp(), sTo.date().date().toString(),
                sTo.date().version(), "i" + sTo.date().intradayStamp(), sTo.view().signature(), sFrom.view().signature(), sTo.res().canonical(),
                sTo.set().digest(), sFrom.set().digest(), String.valueOf(calc.confidence()), String.valueOf(props.order()), props.windowLines(),
                String.valueOf(props.splitAmendments()), String.valueOf(props.top()), String.valueOf(drivers));
        Result hit = cache.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        Result r = new Run(p, sFrom, sTo, calc, drivers).result(t0);
        cache.put(key, r);
        return r;
    }

    /** The Matrix view: the scope and each child node (at most {@code matrix-max}), the steps across. */
    Map<String, Object> matrix(Principal p, String scope, LocalDate from, LocalDate to, Double confidence) {
        Result top = explain(p, scope, from, to, confidence, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(top, 0));
        @SuppressWarnings("unchecked")
        String node = (String) top.answer().get("node");
        int more = 0;
        if (node != null) {
            VarDate d = svc.date(top.to());
            VarBook.Node n = d.book().node(node);
            List<String> kids = n == null ? List.of() : n.children();
            for (String c : kids) {
                if (rows.size() > props.matrixMax()) {
                    more++;
                    continue;
                }
                try {
                    rows.add(row(explain(p, "NODE:" + c, top.from(), top.to(), confidence, false), 1));
                } catch (DrishtiException e) {
                    if (e.errorCode() != ErrorCode.FORBIDDEN) {
                        throw e;
                    }
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", top.answer().get("scope"));
        m.put("from", top.from().toString());
        m.put("to", top.to().toString());
        m.put("steps", top.answer().get("lines"));
        m.put("rows", rows);
        m.put("more", more);
        m.put("note", more == 0 ? "each row's steps add up to its change" : more + " more children not shown (drishti.var.explain.matrix-max)");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Result r, int depth) {
        Map<String, Object> a = r.answer();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", a.get("canonical"));
        m.put("label", a.get("label"));
        m.put("depth", depth);
        m.put("varFrom", a.get("varFrom"));
        Map<String, Object> eff = new LinkedHashMap<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) a.get("lines")) {
            eff.put(String.valueOf(l.get("key")), l.get("effect"));
        }
        m.put("effects", eff);
        m.put("varTo", a.get("varTo"));
        m.put("change", a.get("change"));
        m.put("limit", a.get("limit"));
        m.put("sentence", ((List<String>) a.get("sentences")).isEmpty() ? "" : ((List<String>) a.get("sentences")).get(0));
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------------------- one run

    /** One explanation being worked out. */
    private final class Run {
        final Principal p;
        final VarService.Scoped sFrom;
        final VarService.Scoped sTo;
        final VarBook fb;
        final VarBook tb;
        final VarCalc calc;
        final boolean drivers;
        final List<String> warnings = new ArrayList<>();
        // the churn, as ordinals of each date
        final OrdinalSet.Builder outB = new OrdinalSet.Builder();
        final OrdinalSet.Builder cancelledB = new OrdinalSet.Builder();
        final OrdinalSet.Builder inB = new OrdinalSet.Builder();
        final OrdinalSet.Builder movedInFromB = new OrdinalSet.Builder();
        final OrdinalSet.Builder movedInToB = new OrdinalSet.Builder();
        final OrdinalSet.Builder movedOutFromB = new OrdinalSet.Builder();
        final OrdinalSet.Builder movedOutToB = new OrdinalSet.Builder();
        final OrdinalSet.Builder amendedFromB = new OrdinalSet.Builder();
        final OrdinalSet.Builder amendedToB = new OrdinalSet.Builder();
        int out;
        int in;
        int movedIn;
        int movedOut;
        int amended;
        boolean marks;

        Run(Principal p, VarService.Scoped sFrom, VarService.Scoped sTo, VarCalc calc, boolean drivers) {
            this.p = p;
            this.sFrom = sFrom;
            this.sTo = sTo;
            this.fb = sFrom.date().book();
            this.tb = sTo.date().book();
            this.calc = calc;
            this.drivers = drivers;
        }

        Result result(long t0) {
            churn();
            List<LocalDate> fromDays = fb.scenarioDates();
            List<LocalDate> toDays = tb.scenarioDates();
            if (sFrom.vector().length != fromDays.size() || sTo.vector().length != toDays.size()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the vectors and the scenario days of a date differ in length: explained on whole sets only");
            }
            Map<String, Object> official = new LinkedHashMap<>();
            double[] captured = captured(official, fromDays.size());
            OrdinalSet out = outB.build();
            OrdinalSet amendedF = amendedFromB.build();
            OrdinalSet amendedT = amendedToB.build();
            boolean split = props.splitAmendments() && amended > 0;
            VarExplainMath.Inputs in = new VarExplainMath.Inputs(fromDays, toDays, captured, sFrom.vector(), sTo.vector(),
                    sum(fb, movedInFromB.build()), sum(fb, movedOutFromB.build()), sum(fb, out), split ? sum(fb, amendedF) : null,
                    sum(tb, inB.build()), split ? sum(tb, amendedT) : null, sum(tb, movedInToB.build()), sum(tb, movedOutToB.build()));
            VarExplainMath.Days days = VarExplainMath.Days.of(fromDays, toDays);
            int common = days.commonCount();
            boolean setChange = common * 2 < toDays.size() || common * 2 < fromDays.size();
            List<Op> ops = VarExplainMath.plan(VarExplainMath.causes(props.order()), split, setChange);
            VarExplainMath.Result math = VarExplainMath.run(in, ops, calc);
            Map<String, Object> answer = answer(math, official, setChange, split);
            answer.put("computeMs", (System.nanoTime() - t0) / 1_000_000);
            return new Result(answer, in, math, calc, sTo.label(), sTo.res().canonical(), sFrom.date().date(), sTo.date().date());
        }

        /** Tells the trades apart by id: out, in, moved by the hierarchy, amended. */
        void churn() {
            OrdinalSet a = sFrom.set();
            OrdinalSet b = sTo.set();
            String actionCol = tb.trades().has("TRADE_ACTION") ? "TRADE_ACTION" : tb.trades().has("TRADE_STATUS") ? "TRADE_STATUS" : null;
            marks = actionCol != null;
            a.forEach(o -> {
                String id = fb.tradeId(o);
                int t = tb.tradeOrdinal(id);
                if (t < 0 || tb.cancelled(t)) {
                    outB.add(o);
                    out++;
                    if (t >= 0) {
                        cancelledB.add(o);
                    }
                } else if (!b.contains(t)) {
                    movedOutFromB.add(o);
                    movedOutToB.add(t);
                    movedOut++;
                }
            });
            b.forEach(t -> {
                String id = tb.tradeId(t);
                int o = fb.tradeOrdinal(id);
                if (o < 0 || fb.cancelled(o)) {
                    inB.add(t);
                    in++;
                    return;
                }
                if (!a.contains(o)) {
                    movedInFromB.add(o);
                    movedInToB.add(t);
                    movedIn++;
                }
                if (actionCol != null) {
                    String act = tb.trades().text(actionCol, t);
                    if (act != null && AMENDED.contains(act.strip().toUpperCase(Locale.ROOT))) {
                        amendedFromB.add(o);
                        amendedToB.add(t);
                        amended++;
                    }
                }
            });
        }

        /** The earlier date's official vector (S0) when the scope is an official node seen whole; null (with the reason) otherwise. */
        @SuppressWarnings("unchecked")
        double[] captured(Map<String, Object> official, int n) {
            String node = sFrom.res().node();
            LocalDate d = sFrom.date().date();
            official.put("captured", false);
            if (node == null || !sFrom.res().canonical().equals("NODE:" + node) && !sFrom.kind().equals("node")) {
                official.put("note", "not an official node: the explanation starts from " + d + "'s figure computed now");
                return null;
            }
            if (sFrom.hidden() > 0) {
                official.put("note", "you see part of this node: the explanation starts from " + d + "'s figure of what you see, computed now");
                return null;
            }
            if (!VarDate.USD.equals(sFrom.date().currency())) {
                official.put("note", "explained in USD");
                return null;
            }
            Map<String, Object> idx = svc.official().index(d).orElse(null);
            Object f = idx == null || !(idx.get("nodes") instanceof Map<?, ?> nodes) ? null : nodes.get(node);
            if (!(f instanceof Map<?, ?> fig) || fig.get("captureId") == null) {
                official.put("note", "no official capture of " + d + " for this node: the explanation starts from " + d + "'s figure computed now "
                        + "(restatement is not measured)");
                return null;
            }
            String id = String.valueOf(fig.get("captureId"));
            Map<String, Object> cap = svc.official().docs().value(VarOfficialStore.FOLDER + id + ".json", Map.class).map(x -> (Map<String, Object>) x)
                    .orElse(null);
            if (cap == null || !(cap.get("vector") instanceof List<?> vec) || vec.size() != n) {
                official.put("note", "the official capture " + id + " of " + d + " has no vector of the date's " + n + " scenarios: the explanation "
                        + "starts from the figure computed now");
                return null;
            }
            official.put("captured", true);
            official.put("captureId", id);
            official.put("resultVersion", idx.get("resultVersion"));
            official.put("computedAt", idx.get("computedAt"));
            official.put("var", cap.get("var"));
            official.put("dataVersion", cap.get("dataVersion"));
            boolean same = String.valueOf(cap.get("dataVersion")).equals(sFrom.date().version())
                    && String.valueOf(cap.get("membershipHash")).equals(sFrom.set().digest());
            if (same) {
                official.put("note", "the official capture " + id + " (result version " + idx.get("resultVersion") + "): " + d
                        + "'s data and membership are unchanged since, so there is no restatement");
                return null;                                            // S0 = S1 to the bit: the capture's vector is kept to whole units
            }
            official.put("note", "the official capture " + id + " (result version " + idx.get("resultVersion") + ", data " + cap.get("dataVersion")
                    + "); " + d + "'s data now: " + sFrom.date().version() + ". The official vector is kept to whole units");
            double[] v = new double[n];
            for (int j = 0; j < n; j++) {
                v[j] = vec.get(j) instanceof Number x ? x.doubleValue() : 0;
            }
            return v;
        }

        double[] sum(VarBook b, OrdinalSet s) {
            return s.isEmpty() ? null : b.vector(s, svc.sums());
        }

        // ------------------------------------------------------------------------------------------------------------- the answer

        Map<String, Object> answer(VarExplainMath.Result math, Map<String, Object> official, boolean setChange, boolean split) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scope", sTo.res().canonical());
            m.put("canonical", sTo.res().canonical());
            m.put("label", sTo.label());
            m.put("kind", sTo.kind());
            m.put("node", sTo.res().node());
            m.put("from", sFrom.date().date().toString());
            m.put("to", sTo.date().date().toString());
            m.put("currency", VarDate.USD);
            m.put("horizon", "1D");
            m.put("confidence", calc.confidence());
            m.put("rankRule", calc.rule() == VarMath.RankRule.INTERPOLATED ? "interpolated" : "kth-worst");
            m.put("method", "sequence");
            m.put("shapley", "planned: an order-free variant averaging every order of the causes (not built)");
            m.put("order", props.order());
            m.put("windowLines", props.windowLines());
            m.put("scenarioSetChange", setChange);
            m.put("official", official);
            List<State> st = math.states();
            m.put("varFrom", st.get(0).var());
            m.put("varTo", math.last().var());
            m.put("change", math.change());
            m.put("sumOfSteps", math.sumOfSteps());
            m.put("addsUp", Math.abs(math.sumOfSteps() - math.change()) < CENT);
            m.put("states", states(math));
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Step s : math.steps()) {
                steps.add(step(math, s));
            }
            m.put("steps", steps);
            m.put("lines", lines(steps));
            m.put("orderFree", orderFree(math));
            m.put("limit", limit(math));
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("tradesFrom", sFrom.set().size());
            counts.put("tradesTo", sTo.set().size());
            counts.put("out", out);
            counts.put("cancelled", cancelledB.build().size());
            counts.put("in", in);
            counts.put("movedIn", movedIn);
            counts.put("movedOut", movedOut);
            counts.put("amended", amended);
            m.put("counts", counts);
            if (!marks) {
                warnings.add("the trade feed's action (TRADE_ACTION) is not held by the engine: amended trades are in the market step");
            } else if (amended > 0 && !split) {
                warnings.add(amended + " amended trades are in the market step (drishti.var.explain.split-amendments is off)");
            }
            if (setChange) {
                warnings.add("the scenario set was replaced, not rolled (" + math.days().commonCount() + " days in common): the window and the "
                        + "market are one step");
            }
            warnings.addAll(sFrom.date().warnings());
            warnings.addAll(sTo.date().warnings());
            m.put("sentences", sentences(math, steps));
            m.put("warnings", warnings);
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("fromVersion", sFrom.date().version());
            prov.put("toVersion", sTo.date().version());
            prov.put("fromScenarioSet", fb.scenarioSet());
            prov.put("toScenarioSet", tb.scenarioSet());
            prov.put("fromScenarios", fb.scenarioDates().size());
            prov.put("toScenarios", tb.scenarioDates().size());
            prov.put("commonScenarios", math.days().commonCount());
            prov.put("summed", "the two dates' scope totals and the churn's vectors (" + (out + in + movedIn + movedOut + 2 * amended)
                    + " trades), never the whole book again");
            prov.put("hidden", Math.max(sFrom.hidden(), sTo.hidden()));
            m.put("provenance", prov);
            return m;
        }

        List<Map<String, Object>> states(VarExplainMath.Result math) {
            List<Map<String, Object>> out = new ArrayList<>();
            int trades = sFrom.set().size();
            boolean hier = false;
            for (State s : math.states()) {
                if (s.op() != null) {
                    switch (s.op()) {
                        case HIER -> {
                            trades += movedIn - movedOut;
                            hier = true;
                        }
                        case OUT -> trades -= Run.this.out;
                        case AMEND_OUT -> trades -= amended;
                        case IN -> trades += in;
                        case AMEND_IN -> trades += amended;
                        default -> { }
                    }
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("after", s.op() == null ? null : s.op().key);
                m.put("what", s.op() == null ? (math.ops().contains(Op.RESTATE) && s.index() == 0 && !java.util.Arrays.equals(s.v(),
                        math.states().get(Math.min(1, math.states().size() - 1)).v()) ? "the earlier date as officially captured"
                        : "the earlier date") : s.op().label);
                m.put("trades", trades);
                m.put("vectors", s.today() ? sTo.date().date().toString() : sFrom.date().date().toString());
                m.put("days", s.domain() == VarExplainMath.Domain.FROM ? "earlier set" : s.domain() == VarExplainMath.Domain.TO ? "later set"
                        : "common days");
                m.put("scenarios", s.n());
                m.put("hierarchy", hier ? sTo.date().date().toString() : sFrom.date().date().toString());
                m.put("var", s.var());
                m.put("k", s.k());
                m.put("varScenario", s.varDay() < 0 ? null : math.days().union().get(s.varDay()).toString());
                m.put("effect", s.index() == 0 ? null : s.var() - math.states().get(s.index() - 1).var());
                out.add(m);
            }
            return out;
        }

        Map<String, Object> step(VarExplainMath.Result math, Step s) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.index());
            m.put("key", s.op().key);
            m.put("label", label(s, math));
            m.put("reads", s.op().reads);
            m.put("from", s.before().id());
            m.put("to", s.after().id());
            m.put("effect", s.effect());
            m.put("varBefore", s.before().var());
            m.put("varAfter", s.after().var());
            String vb = day(math, s.before().varDay());
            String va = day(math, s.after().varDay());
            m.put("varScenarioBefore", vb);
            m.put("varScenarioAfter", va);
            m.put("varScenarioChanged", !java.util.Objects.equals(vb, va));
            m.put("drivers", drivers ? drivers(math, s) : null);
            return m;
        }

        String label(Step s, VarExplainMath.Result math) {
            if (s.op() == Op.DROP || s.op() == Op.ADD) {
                int n = dropAdd(math, s).size();
                if (n > 1) {
                    return s.op() == Op.DROP ? n + " oldest scenarios dropped" : n + " newest scenarios added";
                }
            }
            return s.op().label;
        }

        String day(VarExplainMath.Result math, int u) {
            return u < 0 ? null : math.days().union().get(u).toString();
        }

        /** The display lines: the steps, the two window lines summed into one when {@code window-lines: one}. */
        List<Map<String, Object>> lines(List<Map<String, Object>> steps) {
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, Object> roll = null;
            for (Map<String, Object> s : steps) {
                String k = String.valueOf(s.get("key"));
                if (props.windowLines().equals("one") && (k.equals("window-drop") || k.equals("window-add"))) {
                    if (roll == null) {
                        roll = new LinkedHashMap<>();
                        roll.put("key", "window");
                        roll.put("label", "Window roll");
                        roll.put("effect", 0.0);
                        roll.put("steps", new ArrayList<String>());
                        out.add(roll);
                    }
                    roll.put("effect", (double) roll.get("effect") + (double) s.get("effect"));
                    @SuppressWarnings("unchecked")
                    List<String> ks = (List<String>) roll.get("steps");
                    ks.add(k);
                    continue;
                }
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("key", k);
                l.put("label", s.get("label"));
                l.put("effect", s.get("effect"));
                l.put("steps", List.of(k));
                out.add(l);
            }
            return out;
        }

        // ------------------------------------------------------------------------------------------------------------- the drivers

        Map<String, Object> drivers(VarExplainMath.Result math, Step s) {
            return switch (s.op()) {
                case RESTATE -> restatement(s);
                case HIER -> moved(math, s);
                case OUT -> trades(math, s, outB.build(), false, "trade");
                case AMEND_OUT -> trades(math, s, amendedFromB.build(), false, "amended trade");
                case IN -> trades(math, s, inB.build(), true, "trade");
                case AMEND_IN -> trades(math, s, amendedToB.build(), true, "amended trade");
                case DROP, ADD -> window(math, s);
                case MARKET, SET_CHANGE -> market(math, s);
            };
        }

        Map<String, Object> restatement(Step s) {
            Map<String, Object> m = base("restatement", s.effect());
            m.put("note", s.effect() == 0 ? "no restatement: the earlier date's figures are as officially captured"
                    : "the earlier date's VaR as officially captured, against the same date's data now");
            m.put("rows", List.of());
            return m;
        }

        Map<String, Object> base(String kind, double effect) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("effect", effect);
            return m;
        }

        /** Trades out or in (or amended): each one's incremental VaR in the state that holds it, the top N, the rest in one line. */
        Map<String, Object> trades(VarExplainMath.Result math, Step s, OrdinalSet set, boolean later, String noun) {
            State holder = later ? s.after() : s.before();
            VarBook b = later ? tb : fb;
            VarExplainMath.Days d = math.days();
            Map<String, Object> m = base("trades", s.effect());
            List<Integer> os = new ArrayList<>();
            set.forEach(os::add);
            m.put("count", os.size());
            if (os.size() > props.driversMax()) {                   // the largest by their share of the holding state's tail
                os.sort(Comparator.comparingDouble((Integer o) -> -Math.abs(VarExplainMath.component(holder, lift(d, b, o, later)))));
                os = new ArrayList<>(os.subList(0, props.driversMax()));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            double base = holder.var();
            OrdinalSet cancelled = cancelledB.build();
            for (int o : os) {
                double[] v = lift(d, b, o, later);
                double inc = base - VarExplainMath.varWithout(holder, v, calc);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", b.tradeId(o));
                r.put("book", b.trades().text("TT_BOOK", o));
                r.put("desk", b.trades().text("DESK", o));
                r.put("product", b.trades().text("PRODUCT_TYPE", o));
                if (!later && s.op() == Op.OUT) {
                    r.put("reason", cancelled.contains(o) ? "cancelled on " + sTo.date().date() : "no vector on " + sTo.date().date());
                }
                r.put("incrementalVar", later ? inc : -inc);              // the step's sign: a trade out lowers VaR by its incremental VaR
                rows.add(r);
            }
            rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> -Math.abs((double) r.get("incrementalVar"))));
            return topAndRest(m, rows, "incrementalVar", s.effect(), "the other " + noun + "s and the interaction");
        }

        double[] lift(VarExplainMath.Days d, VarBook b, int o, boolean later) {
            double[] v = b.tradeVector(o);
            return later ? d.liftTo(v) : d.liftFrom(v);
        }

        Map<String, Object> topAndRest(Map<String, Object> m, List<Map<String, Object>> rows, String field, double effect, String restLabel) {
            List<Map<String, Object>> top = new ArrayList<>(rows.subList(0, Math.min(props.top(), rows.size())));
            double listed = 0;
            for (Map<String, Object> r : top) {
                listed += (double) r.get(field);
            }
            m.put("rows", top);
            m.put("shown", top.size());
            m.put("rest", effect - listed);
            m.put("restLabel", restLabel);
            m.put("field", field);
            m.put("note", "incremental VaR (the state's VaR with and without each) does not add up: the last line closes the step exactly");
            return m;
        }

        /** The hierarchy step: the books whose trades moved in or out, each group's incremental VaR in the state that holds it. */
        Map<String, Object> moved(VarExplainMath.Result math, Step s) {
            boolean today = s.before().today();
            VarBook b = today ? tb : fb;
            OrdinalSet ins = today ? movedInToB.build() : movedInFromB.build();
            OrdinalSet outs = today ? movedOutToB.build() : movedOutFromB.build();
            Map<String, Object> m = base("books", s.effect());
            m.put("count", movedIn + movedOut);
            List<Map<String, Object>> rows = new ArrayList<>();
            group(math, rows, b, ins, today, s.after(), "moved in", 1);
            group(math, rows, b, outs, today, s.before(), "moved out", -1);
            rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> -Math.abs((double) r.get("incrementalVar"))));
            topAndRest(m, rows, "incrementalVar", s.effect(), "the interaction");
            m.put("note", movedIn + movedOut == 0 ? "no trade moved in or out of the scope by the hierarchy or the rules"
                    : "books whose trades moved into or out of the scope with no trade changing; each with its incremental VaR");
            return m;
        }

        void group(VarExplainMath.Result math, List<Map<String, Object>> rows, VarBook b, OrdinalSet set, boolean today, State holder, String how, int sign) {
            Map<String, OrdinalSet.Builder> byBook = new LinkedHashMap<>();
            set.forEach(o -> byBook.computeIfAbsent(String.valueOf(b.trades().text("TT_BOOK", o)), k -> new OrdinalSet.Builder()).add(o));
            for (Map.Entry<String, OrdinalSet.Builder> e : byBook.entrySet()) {
                OrdinalSet g = e.getValue().build();
                double[] v = b.vector(g, svc.sums());
                double[] u = today ? math.days().liftTo(v) : math.days().liftFrom(v);
                double inc = holder.var() - VarExplainMath.varWithout(holder, u, calc);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", e.getKey());
                r.put("how", how);
                r.put("trades", g.size());
                r.put("incrementalVar", sign * inc);
                rows.add(r);
            }
        }

        List<Integer> dropAdd(VarExplainMath.Result math, Step s) {
            List<Integer> out = new ArrayList<>();
            for (int u = 0; u < math.days().size(); u++) {
                boolean was = !Double.isNaN(s.before().v()[u]);
                boolean is = !Double.isNaN(s.after().v()[u]);
                if (s.op() == Op.DROP ? was && !is : !was && is) {
                    out.add(u);
                }
            }
            return out;
        }

        /** The window: the days that left or entered, with their P&amp;L, rank and whether they were in the tail. */
        Map<String, Object> window(VarExplainMath.Result math, Step s) {
            boolean drop = s.op() == Op.DROP;
            State in = drop ? s.before() : s.after();
            Map<String, Object> m = base("days", s.effect());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int u : dropAdd(math, s)) {
                int rank = in.rank(u);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("day", math.days().union().get(u).toString());
                r.put("pnl", in.v()[u]);
                r.put("rank", rank);
                r.put("inTail", rank > 0 && rank <= in.k());
                r.put("varScenario", u == in.varDay());
                rows.add(r);
            }
            m.put("count", rows.size());
            m.put("rows", rows.size() > 50 ? rows.subList(0, 50) : rows);
            m.put("shown", Math.min(50, rows.size()));
            m.put("k", in.k());
            m.put("note", drop ? "at the same k, a day leaving can only lower VaR or leave it: when it was in the tail (rank ≤ k) the next day takes its rank"
                    : "at the same k, a day entering can only raise VaR or leave it: when it lands in the tail (rank ≤ k) it pushes the VaR rank one day better");
            return m;
        }

        /** The market step: each child's component VaR before and after (they add up to each state's VaR, so the changes to the step). */
        Map<String, Object> market(VarExplainMath.Result math, Step s) {
            boolean hierFirst = math.ops().indexOf(Op.HIER) < math.ops().indexOf(s.op());
            Grouping g = Grouping.of(tb, sTo);
            Grouping gf = Grouping.of(fb, sFrom);
            Map<String, OrdinalSet.Builder> f = new LinkedHashMap<>();
            Map<String, OrdinalSet.Builder> t = new LinkedHashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            OrdinalSet in = inB.build();
            OrdinalSet am = amendedToB.build();
            OrdinalSet movedInTo = movedInToB.build();
            boolean split = math.ops().contains(Op.AMEND_IN);
            sTo.set().forEach(o -> {                                      // the trades on both dates in the scope today
                if (in.contains(o) || split && am.contains(o)) {
                    return;
                }
                int fo = fb.tradeOrdinal(tb.tradeId(o));
                if (!hierFirst && movedInTo.contains(o)) {
                    return;                                                // still outside the scope in the market state
                }
                String k = g.keyOf(tb, o);
                labels.putIfAbsent(k, g.label(k));
                f.computeIfAbsent(k, x -> new OrdinalSet.Builder()).add(fo);
                t.computeIfAbsent(k, x -> new OrdinalSet.Builder()).add(o);
            });
            if (!hierFirst) {                                              // trades the hierarchy takes out later are still in
                OrdinalSet mo = movedOutToB.build();
                mo.forEach(o -> {
                    int fo = fb.tradeOrdinal(tb.tradeId(o));
                    String k = "~" + gf.keyOf(fb, fo);
                    labels.putIfAbsent(k, gf.label(gf.keyOf(fb, fo)) + " (leaves at the hierarchy step)");
                    f.computeIfAbsent(k, x -> new OrdinalSet.Builder()).add(fo);
                    t.computeIfAbsent(k, x -> new OrdinalSet.Builder()).add(o);
                });
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            double sumB = 0;
            double sumA = 0;
            for (String k : t.keySet()) {
                double cb = VarExplainMath.component(s.before(), math.days().liftFrom(fb.vector(f.get(k).build(), svc.sums())));
                double ca = VarExplainMath.component(s.after(), math.days().liftTo(tb.vector(t.get(k).build(), svc.sums())));
                sumB += cb;
                sumA += ca;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", k);
                r.put("label", labels.get(k));
                r.put("scope", g.scopeOf(k));
                r.put("componentBefore", cb);
                r.put("componentAfter", ca);
                r.put("change", ca - cb);
                r.put("share", s.effect() == 0 ? 0 : (ca - cb) / s.effect());
                rows.add(r);
            }
            rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> -Math.abs((double) r.get("change"))));
            Map<String, Object> m = base("children", s.effect());
            m.put("by", g.by());
            m.put("count", rows.size());
            m.put("rows", rows);
            m.put("shown", rows.size());
            m.put("sum", sumA - sumB);
            m.put("addsUp", Math.abs(sumA - sumB - s.effect()) < CENT);
            m.put("note", "each part's component VaR in " + s.before().id() + " and " + s.after().id() + " (they add up to each state's VaR), so the "
                    + "changes add up to the step");
            return m;
        }

        // ------------------------------------------------------------------------------------------------- order-free view and limit

        /** Each child's component VaR on the later date less on the earlier (its own hierarchy each day); adds up to the change. */
        Map<String, Object> orderFree(VarExplainMath.Result math) {
            Grouping gt = Grouping.of(tb, sTo);
            Grouping gf = Grouping.of(fb, sFrom);
            Map<String, double[]> partsT = gt.parts(tb, sTo.set(), svc);
            Map<String, double[]> partsF = gf.parts(fb, sFrom.set(), svc);
            VarCalc.Measures mt = calc.measures(sTo.vector());
            VarCalc.Measures mf = calc.measures(sFrom.vector());
            Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
            double total = 0;
            for (Map.Entry<String, double[]> e : partsT.entrySet()) {
                Map<String, Object> r = rows.computeIfAbsent(e.getKey(), k -> freeRow(k, gt.label(k), gt.scopeOf(k)));
                r.put("to", VarMath.component(e.getValue(), mt.tail(), mt.scale()));
            }
            for (Map.Entry<String, double[]> e : partsF.entrySet()) {
                Map<String, Object> r = rows.computeIfAbsent(e.getKey(), k -> freeRow(k, gf.label(k), gf.scopeOf(k)));
                r.put("from", VarMath.component(e.getValue(), mf.tail(), mf.scale()));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows.values()) {
                double ch = (double) r.get("to") - (double) r.get("from");
                r.put("change", ch);
                r.put("note", (double) r.get("from") == 0 && !partsF.containsKey(r.get("id")) ? "arrived" : !partsT.containsKey(r.get("id")) ? "left" : "");
                total += ch;
                out.add(r);
            }
            out.sort(Comparator.comparingDouble((Map<String, Object> r) -> -Math.abs((double) r.get("change"))));
            double restated = calc.var(sFrom.vector()) - math.first().var();
            if (Math.abs(restated) > 0) {
                Map<String, Object> r = freeRow("restatement", "Restatement since the official run", null);
                r.put("from", math.first().var());
                r.put("to", calc.var(sFrom.vector()));
                r.put("change", restated);
                r.put("note", "the earlier date's VaR now less as captured");
                out.add(r);
                total += restated;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("by", gt.by());
            m.put("rows", out);
            m.put("total", total);
            m.put("addsUp", Math.abs(total - math.change()) < CENT);
            m.put("note", "order-free: each part's component VaR on " + sTo.date().date() + " less on " + sFrom.date().date()
                    + "; a part that left or arrived shows its whole component; adds up to the change");
            return m;
        }

        Map<String, Object> freeRow(String id, String label, String scope) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", id);
            r.put("label", label);
            r.put("scope", scope);
            r.put("from", 0.0);
            r.put("to", 0.0);
            return r;
        }

        /** The limit-use line: the use's change split into the VaR's change and the limit's own change (a node's limit on each date). */
        Map<String, Object> limit(VarExplainMath.Result math) {
            String node = sTo.res().node();
            if (node == null || fb.node(node) == null || tb.node(node) == null) {
                return null;
            }
            double l0 = fb.node(node).limit();
            double l1 = tb.node(node).limit();
            if (!(l0 > 0) || !(l1 > 0)) {
                return null;
            }
            double v0 = math.first().var();
            double v1 = math.last().var();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("limitFrom", l0);
            m.put("limitTo", l1);
            m.put("useFrom", v0 / l0);
            m.put("useTo", v1 / l1);
            m.put("fromVar", (v1 - v0) / l0);
            m.put("fromLimit", v1 * (1 / l1 - 1 / l0));
            m.put("note", "the use's change = the VaR change over the earlier limit + the later VaR times the change of 1/limit");
            return m;
        }

        // ------------------------------------------------------------------------------------------------------------- sentences

        List<String> sentences(VarExplainMath.Result math, List<Map<String, Object>> steps) {
            List<String> out = new ArrayList<>();
            double ch = math.change();
            out.add("VaR of " + sTo.label() + " " + (ch >= 0 ? "rose" : "fell") + " by " + money(Math.abs(ch)) + ", from " + money(math.first().var())
                    + " on " + sFrom.date().date() + " to " + money(math.last().var()) + " on " + sTo.date().date() + "; the steps below add up to it exactly.");
            List<Step> big = new ArrayList<>(math.steps());
            big.sort(Comparator.comparingDouble((Step s) -> -Math.abs(s.effect())));
            List<String> parts = new ArrayList<>();
            for (Step s : big.subList(0, Math.min(3, big.size()))) {
                if (Math.abs(s.effect()) >= CENT) {
                    parts.add(label(s, math).toLowerCase(Locale.ROOT) + " " + signed(s.effect()));
                }
            }
            if (!parts.isEmpty()) {
                out.add("The largest causes: " + String.join(", ", parts) + ".");
            }
            for (Step s : math.steps()) {
                String vb = day(math, s.before().varDay());
                String va = day(math, s.after().varDay());
                if (vb != null && va != null && !vb.equals(va)) {
                    out.add("At the " + label(s, math).toLowerCase(Locale.ROOT) + " step the VaR scenario changed from " + vb + " to " + va + ".");
                }
            }
            if (out.size() == 2 || out.size() == 1) {
                String v0 = day(math, math.first().varDay());
                if (v0 != null && v0.equals(day(math, math.last().varDay()))) {
                    out.add("The VaR scenario is " + v0 + " on both dates.");
                }
            }
            out.add("Order: " + String.join(", ", props.order()) + " (drishti.var.explain.order); another order gives other step sizes with the same total.");
            return out;
        }
    }

    static String money(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) {
            return String.format(Locale.ROOT, "%.2fbn", v / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.ROOT, "%.2fm", v / 1e6);
        }
        return String.format(Locale.ROOT, "%,.0f", v);
    }

    static String signed(double v) {
        return (v >= 0 ? "+" : "−") + money(Math.abs(v));
    }

    // ------------------------------------------------------------------------------------------------------------------------- parts

    /**
     * How a scope is split into parts: a node with children by its child nodes (that date's hierarchy), anything else by book
     * ({@code TT_BOOK}).
     */
    record Grouping(String by, List<VarBook.Node> children) {

        static Grouping of(VarBook b, VarService.Scoped s) {
            String node = s.res().node();
            VarBook.Node n = node == null ? null : b.node(node);
            if (n != null && !n.children().isEmpty()) {
                List<VarBook.Node> kids = new ArrayList<>();
                for (String c : n.children()) {
                    if (b.node(c) != null) {
                        kids.add(b.node(c));
                    }
                }
                return new Grouping("node", kids);
            }
            return new Grouping("book", List.of());
        }

        String keyOf(VarBook b, int o) {
            if (by.equals("node")) {
                for (VarBook.Node c : children) {
                    if (c.members().contains(o)) {
                        return c.id();
                    }
                }
                return "(no child)";
            }
            return String.valueOf(b.trades().text("TT_BOOK", o));
        }

        String label(String key) {
            if (by.equals("node")) {
                for (VarBook.Node c : children) {
                    if (c.id().equals(key)) {
                        return c.name();
                    }
                }
            }
            return key;
        }

        String scopeOf(String key) {
            if (key.startsWith("~") || key.startsWith("(")) {
                return null;
            }
            return by.equals("node") ? "NODE:" + key : "BOOK:" + key;
        }

        /** Each part's vector of a set. */
        Map<String, double[]> parts(VarBook b, OrdinalSet set, VarService svc) {
            Map<String, double[]> out = new LinkedHashMap<>();
            if (by.equals("node")) {
                OrdinalSet rest = set;
                for (VarBook.Node c : children) {
                    OrdinalSet m = c.members().and(set);
                    rest = rest.andNot(c.members());
                    if (!m.isEmpty()) {
                        out.put(c.id(), m.equals(c.members()) ? b.aggregate(c.id()) : b.vector(m, svc.sums()));
                    }
                }
                if (!rest.isEmpty()) {
                    out.put("(no child)", b.vector(rest, svc.sums()));
                }
                return out;
            }
            Map<String, OrdinalSet.Builder> g = new LinkedHashMap<>();
            set.forEach(o -> g.computeIfAbsent(String.valueOf(b.trades().text("TT_BOOK", o)), k -> new OrdinalSet.Builder()).add(o));
            g.forEach((k, v) -> out.put(k, b.vector(v.build(), svc.sums())));
            return out;
        }
    }
}
