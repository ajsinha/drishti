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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Engines;
import com.ash.drishti.kernel.api.services.Kernel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * R2 (docs/plans/R2_CUT_PLAN.md, "Switching a route"): the exposure routes answered by Drishti Risk's counterparty engine ({@code ccr})
 * through the kernel's {@link Engines} service, never by a Risk type. One switch per route family, {@code drishti.module.banking.exposure.route}:
 * {@code in-tree} or {@code engine}; a request may name its path ({@code path=engine} or {@code path=in-tree}) so both answers can be compared
 * on one server. The engine's figures are unrounded; the route rounds them to whole units as the in-tree route does and gives the exact
 * doubles beside them ({@code ...Exact}) for the raw-bit check. What the engine path does not answer yet is refused in one sentence naming
 * {@code path=in-tree}, never answered half.
 */
@Component
public class ExposureEnginePath {

    /** The engine's id (config/engines/ccr.yaml). */
    public static final String ENGINE = "ccr";
    static final String SETTING = "drishti.module.banking.exposure.route";   // engines/exposure.yaml

    private final ObjectProvider<Engines> engines;
    private final Environment env;
    private final ObjectProvider<ExposureService> svc;
    private final ObjectProvider<ExposureViews> views;

    ExposureEnginePath(ObjectProvider<Engines> engines, Environment env, ObjectProvider<ExposureService> svc, ObjectProvider<ExposureViews> views) {
        this.engines = engines;
        this.env = env;
        this.svc = svc;
        this.views = views;
    }

    /** True when this request is answered by the engine: its own {@code path}, else the family's switch. */
    public boolean engine(String requested) {
        String p = requested != null && !requested.isBlank() ? requested : env.getProperty(SETTING, "in-tree");
        return switch (p.trim().toLowerCase(Locale.ROOT)) {
            case "engine" -> true;
            case "in-tree" -> false;
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "path is engine or in-tree, not " + p);
        };
    }

    /** True when the engine {@code ccr} runs here (its JAR in lib/engines and config/engines/ccr.yaml on). */
    public boolean running() {
        Engines e = engines.getIfAvailable();
        return e != null && e.running(ENGINE);
    }

    /** Refuses, in words, the parameters the engine path does not take yet (each is answered in-tree). */
    static void offeredOnly(String run, Boolean strict) {
        if (run != null && !run.isBlank() || strict != null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path answers a scope on the date's run for now; run and strict "
                    + "are answered in-tree (path=in-tree)");
        }
    }

    /**
     * The measure parameters a request may give, as the engine takes them: {@code confidence} (q, the PFE confidence; absent: the engine's)
     * and {@code collateral} (lagged, current or none; absent: the engine's model). Checked as the in-tree route checks them.
     */
    record Measures(String q, String collateral) {
        Map<String, Object> params() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (q != null && !q.isBlank()) {
                double v;
                try {
                    v = Double.parseDouble(q.trim());
                } catch (NumberFormatException e) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "q is a confidence between 0.5 and 0.9999, not " + q);
                }
                if (!(v > 0.5 && v <= 0.9999)) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "q is a confidence between 0.5 and 0.9999, not " + v);
                }
                m.put("confidence", v);
            }
            if (collateral != null && !collateral.isBlank()) {
                String c = collateral.trim().toLowerCase(Locale.ROOT);
                if (!List.of("lagged", "current", "none").contains(c)) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "collateral is lagged, current or none, not " + collateral);
                }
                m.put("collateral", c);
            }
            return m;
        }
    }

    /**
     * A scope's figures and profile through the engine (the in-tree {@code /scope} answer's keys it holds): {@code scope}, {@code businessDate},
     * {@code trades}, {@code ce}, {@code epe}, {@code eepe}, {@code peakEe}, {@code peakPfe} (whole units), {@code peakPfeDate},
     * {@code peakPfeTenor}, {@code profile} (the primary dates), the exact doubles ({@code ceExact}, {@code epeExact}, {@code eepeExact},
     * {@code peakEeExact}, {@code peakPfeExact}), {@code path: engine}.
     */
    public Map<String, Object> scope(Principal p, String scope, LocalDate date, Measures m) {
        String set = nettingSet(scope);
        Map<String, Object> a = ask(p, scope, date, m, set == null ? List.of("exposure-summary", "exposure-profile")
                : List.of("exposure-summary", "exposure-profile", "netting-set-collateral"));
        List<Map<String, Object>> summary = rows(a, "exposure-summary");
        Map<String, Object> s = summary == null || summary.isEmpty() ? null : summary.get(0);
        if (s == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + date + missing(a));
        }
        List<Map<String, Object>> profile = profileRows(a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("businessDate", date.toString());
        out.put("trades", ((Number) s.get("TRADES")).longValue());
        for (String[] f : FIGURES) {
            double x = ((Number) s.get(f[0])).doubleValue();
            out.put(f[1], Math.round(x));
            out.put(f[1] + "Exact", x);
        }
        Object peakDate = s.get("PEAK_PFE_DATE");
        out.put("peakPfeDate", peakDate == null ? null : peakDate.toString());
        out.put("peakPfeTenor", peakDate == null ? null : profile.stream().filter(r -> peakDate.toString().equals(r.get("date")))
                .map(r -> r.get("tenor")).findFirst().orElse(null));
        out.put("profile", profile);
        if (set != null) {
            out.put("collateral", collateral(a, set));
        }
        out.put("path", "engine");
        return out;
    }

    /**
     * Every counterparty's figures through the engine ({@code counterparty-summary}), in counterparty order, for the Exposure Home and
     * the tree: {@code id}, {@code scope} ({@code CPTY:<id>}), {@code nettingSets}, {@code trades}, {@code ce}, {@code epe}, {@code eepe},
     * {@code peakEe}, {@code peakPfe} (whole units), {@code peakPfeDate}, each figure's exact double, and the stored credit limit
     * ({@code limitId}, {@code limit} in whole units as the in-tree tree's {@code limit.limit}; null without one).
     */
    public List<Map<String, Object>> counterparties(Principal p, LocalDate date, Measures m) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path needs the business date: give asOf");
        }
        Map<String, Object> a = call(p, List.of("counterparty-summary"), m.params(), date);   // every counterparty: no scope
        List<Map<String, Object>> rows = rows(a, "counterparty-summary");
        if (rows == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no counterparty exposure on " + date + missing(a));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", r.get("COUNTERPARTY"));
            c.put("scope", "CPTY:" + r.get("COUNTERPARTY"));
            c.put("nettingSets", ((Number) r.get("NETTING_SETS")).longValue());
            c.put("trades", ((Number) r.get("TRADES")).longValue());
            for (String[] f : FIGURES) {
                double x = ((Number) r.get(f[0])).doubleValue();
                c.put(f[1], Math.round(x));
                c.put(f[1] + "Exact", x);
            }
            c.put("peakPfeDate", r.get("PEAK_PFE_DATE") == null ? null : r.get("PEAK_PFE_DATE").toString());
            c.put("limitId", r.get("LIMIT_ID"));
            c.put("limit", r.get("LIMIT") instanceof Number l ? Math.round(l.doubleValue()) : null);
            out.add(c);
        }
        return out;
    }

    /**
     * A pre-deal check through the engine ({@code pre-deal}, {@code pre-deal-buckets}): the exact method only (a template trade, LIKE
     * {@code <trade>}), as the in-tree check's keys it holds: {@code counterparty}, {@code businessDate}, {@code command}, {@code decision},
     * {@code reasons}, {@code template} ({@code tradeId}, {@code factor}), {@code nettingSet.id}, {@code limit} (id, measure, limit, before,
     * after, increment, use before and after, status and the tenor buckets; null without a limit), {@code exposure} (the IMM EAD before
     * and with the trade), {@code path: engine}. A proxy (no template) and a supplied MtM are answered in-tree.
     */
    public Map<String, Object> preDeal(Principal p, Map<String, Object> body, LocalDate date) {
        ExposurePreDealCommand cmd = ExposurePreDealCommand.of(body);
        if (!cmd.exact() || cmd.mtm() != null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path checks a trade like a booked template (LIKE <trade>) "
                    + "for now; a proxy by asset class and maturity, and a supplied MtM, are answered in-tree (path=in-tree)");
        }
        if (date == null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path needs the business date: give asOf");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("counterparty", cmd.counterparty());
        params.put("template", cmd.like());
        params.put("notional", cmd.notional() == null ? 0.0 : cmd.notional());
        params.put("reverse", cmd.reverse());
        params.put("netting-set", cmd.nettingSet() == null ? "" : cmd.nettingSet());
        Map<String, Object> a = call(p, List.of("pre-deal", "pre-deal-buckets"), params, date);
        List<Map<String, Object>> rows = rows(a, "pre-deal");
        if (rows == null || rows.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no pre-deal check of " + cmd.counterparty() + " on "
                    + date + missing(a));
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counterparty", cmd.counterparty());
        out.put("businessDate", date.toString());
        out.put("command", cmd.text());
        out.put("decision", r.get("DECISION"));
        out.put("reasons", List.of(String.valueOf(r.get("REASONS"))));
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("tradeId", r.get("TEMPLATE"));
        template.put("factor", r.get("FACTOR"));
        out.put("template", template);
        Map<String, Object> ns = new LinkedHashMap<>();
        ns.put("id", r.get("NETTING_SET"));
        out.put("nettingSet", ns);
        Map<String, Object> lim = null;
        if (r.get("LIMIT_ID") != null && r.get("LIMIT") instanceof Number amount) {
            lim = new LinkedHashMap<>();
            lim.put("id", r.get("LIMIT_ID"));
            lim.put("measure", r.get("MEASURE"));
            lim.put("limit", Math.round(amount.doubleValue()));
            // the in-tree check reads the what-if's figures in whole units: the use is the whole-unit figure over the limit
            long before = Math.round(((Number) r.get("BEFORE")).doubleValue());
            long after = Math.round(((Number) r.get("AFTER")).doubleValue());
            lim.put("before", before);
            lim.put("after", after);
            lim.put("increment", after - before);
            lim.put("useBefore", before / amount.doubleValue());
            lim.put("useAfter", after / amount.doubleValue());
            List<Map<String, Object>> buckets = new ArrayList<>();
            List<Map<String, Object>> bs = rows(a, "pre-deal-buckets");
            for (Map<String, Object> b : bs == null ? List.<Map<String, Object>>of() : bs) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("bucket", b.get("BUCKET"));
                x.put("limit", Math.round(((Number) b.get("LIMIT")).doubleValue()));
                x.put("before", b.get("BEFORE") instanceof Number n ? Math.round(n.doubleValue()) : null);
                x.put("after", b.get("AFTER") instanceof Number n ? Math.round(n.doubleValue()) : null);
                x.put("useAfter", b.get("AFTER") instanceof Number n ? Math.round(n.doubleValue()) / ((Number) b.get("LIMIT")).doubleValue() : null);
                x.put("status", b.get("STATUS"));
                buckets.add(x);
            }
            // the engine's rows come by key (the bucket's name: 10Y+ before 3-5Y); the in-tree check lists them by tenor, the limit's own order
            buckets.sort(java.util.Comparator.comparingDouble(x -> {
                ExposureDate.Bucket b = ExposureDate.Bucket.of(String.valueOf(x.get("bucket")), 1);
                return b == null ? Double.MAX_VALUE : b.from();
            }));
            lim.put("buckets", buckets);
            lim.put("status", r.get("STATUS"));
        }
        out.put("limit", lim);
        Map<String, Object> exp = new LinkedHashMap<>();
        // EAD = alpha x EEPE, the EEPE in whole units as the in-tree what-if gives it (the engine's EAD over alpha is that EEPE)
        ExposureService es = svc.getIfAvailable();
        double alpha = es == null ? 1.4 : es.props().alpha();
        exp.put("base", Map.of("ead", Math.round(alpha * Math.round(((Number) r.get("EAD_BEFORE")).doubleValue() / alpha))));
        exp.put("with", Map.of("ead", Math.round(alpha * Math.round(((Number) r.get("EAD_AFTER")).doubleValue() / alpha))));
        out.put("exposure", exp);
        out.put("path", "engine");
        return out;
    }

    /**
     * A scope's contributions through the engine ({@code scope-resolution}, {@code exposure-summary}, {@code exposure-profile},
     * {@code exposure-contributions}), as the in-tree {@code /scope/contributions} answers them: {@code scope}, {@code canonical},
     * {@code businessDate}, {@code by} (child: the scope kind's child, as in-tree), {@code figure}, {@code date}, {@code total}, {@code rows}
     * (key, scope, label, kind, value, pct, incremental; the first {@code max-parts}), {@code parts}, {@code truncated}, {@code path: engine}.
     * Refused in words, answered in-tree: parts by counterparty (their names are the host's reference data) or by trade (the trade
     * columns), {@code alone}, {@code profile}, and a viewer whose role has exposure access levels (EXPOSURE_GUIDE.md section 9).
     */
    public Map<String, Object> contributions(Principal p, String scope, LocalDate date, Measures m, String by, String figure, String at, boolean alone,
            boolean profile) {
        if (alone || profile) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path answers contributions without alone and profile for now; "
                    + "they are answered in-tree (path=in-tree)");
        }
        ExposureService es = svc.getIfAvailable();
        if (es != null && p != null && !es.access().levels(p).isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "your role's exposure access levels are applied in-tree; contributions are "
                    + "answered there (path=in-tree)");
        }
        long t0 = System.nanoTime();
        Map<String, Object> a = ask(p, scope, date, m, List.of("scope-resolution", "exposure-summary", "exposure-profile", "exposure-flags", "exposure-run"));
        List<Map<String, Object>> res = rows(a, "scope-resolution");
        List<Map<String, Object>> summary = rows(a, "exposure-summary");
        if (res == null || res.isEmpty() || summary == null || summary.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + date + missing(a));
        }
        String kind = String.valueOf(res.get(0).get("KIND"));
        String mode = by == null || by.isBlank() || by.equalsIgnoreCase("child") ? ExposureViews.childOf(kind) : by.trim().toLowerCase(Locale.ROOT);
        String fig = figure == null || figure.isBlank() ? "PFE" : figure.trim().toUpperCase(Locale.ROOT);
        if (!List.of("EE", "EPE", "PFE").contains(fig)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "figure is EE, EPE or PFE, not " + figure);
        }
        if (!List.of("netting-set", "counterparty", "legal-entity", "trade", "asset", "none").contains(mode)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "by is child, netting-set, counterparty, legal-entity, trade or asset, not " + by);
        }
        if (mode.equals("counterparty") || mode.equals("trade")) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path does not give parts by " + mode + " yet (" + (mode.equals("trade")
                    ? "the trade columns" : "the counterparties' names") + " are the host's); they are answered in-tree (path=in-tree)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", res.get(0).get("CANONICAL"));
        out.put("businessDate", date.toString());
        out.put("by", mode);
        out.put("figure", fig);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (mode.equals("none")) {
            out.put("rows", rows);
            out.put("note", "a trade alone has no parts");
            out.put("path", "engine");
            return out;
        }
        List<Map<String, Object>> points = rows(a, "exposure-profile");
        int i = primary(points, at);
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("scope", scope.isBlank() ? "BANK" : scope);
        prm.put("standalone", false);
        prm.putAll(m.params());
        prm.put("by", mode);
        prm.put("figure", fig);
        prm.put("at", i);
        groups(scope, date, prm);
        Map<String, Object> c = call(p, List.of("exposure-contributions"), prm, date);
        List<Map<String, Object>> cs = rows(c, "exposure-contributions");
        if (cs == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no contributions of " + scope + " on " + date + missing(c));
        }
        cs.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("RANK")).longValue()));
        int max = es == null ? Integer.MAX_VALUE : es.props().maxParts();
        for (Map<String, Object> r : cs.subList(0, Math.min(cs.size(), max))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("key", r.get("KEY"));
            x.put("scope", r.get("SCOPE"));
            x.put("label", r.get("LABEL"));
            x.put("kind", r.get("KIND"));
            x.put("value", Math.round(((Number) r.get("VALUE")).doubleValue()));
            x.put("pct", r.get("PCT"));
            x.put("incremental", r.get("INCREMENTAL") instanceof Number n ? Math.round(n.doubleValue()) : null);
            rows.add(x);
        }
        Map<String, Object> s = summary.get(0);
        double[] ee = points.stream().mapToDouble(r -> ((Number) r.get("EE")).doubleValue()).toArray();
        Object peakDate = s.get("PEAK_PFE_DATE");
        String when = switch (fig) {
            case "EPE" -> null;
            case "PFE" -> i >= 0 ? String.valueOf(points.get(i).get("DATE")) : peakDate != null ? peakDate.toString() : String.valueOf(points.get(0).get("DATE"));
            default -> String.valueOf(points.get(i >= 0 ? i : argmax(ee)).get("DATE"));
        };
        out.put("date", when);
        double total = switch (fig) {
            case "EE" -> i >= 0 ? ee[i] : ((Number) s.get("PEAK_EE")).doubleValue();
            case "EPE" -> ((Number) s.get("EPE")).doubleValue();
            default -> ((Number) s.get("PEAK_PFE")).doubleValue();
        };
        out.put("total", Math.round(total));
        out.put("rows", rows);
        out.put("parts", cs.size());
        out.put("truncated", cs.size() > max);
        out.put("path", "engine");
        audit(es, p, a, date, m, "contributions by=" + mode + " figure=" + fig, t0);
        return out;
    }

    /**
     * The tree's figures through the engine ({@code exposure-tree}, {@code exposure-tree-ee}, {@code exposure-run}): each netting set's,
     * counterparty's, the bank's and each legal entity's EPE, peak PFE and its tenor, peak EE, EE line and flags, the run's id; each
     * counterparty's limit use at the limit's confidence ({@code counterparty-summary} at that confidence when the tree's is another).
     * The tree's shape, names, groups, reference netting sets and the viewer's rules stay the host's ({@link ExposureViews#tree}). Refused
     * in words, answered in-tree: a viewer whose role has exposure access levels.
     */
    Map<String, Object> tree(Principal p, LocalDate asked, String q) {
        ExposureService es = svc.getObject();
        LocalDate latest = es.loader().latest();
        LocalDate date = asked == null || latest != null && asked.isAfter(latest) ? latest : asked;   // as the in-tree route reads the date
        if (date == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths in any connector (kind " + es.props().kinds().paths() + ")");
        }
        es.access().requireOpen(p);
        if (p != null && !es.access().levels(p).isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "your role's exposure access levels are applied in-tree; the tree is "
                    + "answered there (path=in-tree)");
        }
        ExposureService.Ask ask = ExposureService.Ask.of(es.props(), q, null, null, null);
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("confidence", ask.q());
        prm.put("collateral", ask.collateral());
        Map<String, Object> a = call(p, List.of("exposure-tree", "exposure-tree-ee", "exposure-run"), prm, date);
        List<Map<String, Object>> nodes = rows(a, "exposure-tree");
        List<Map<String, Object>> ees = rows(a, "exposure-tree-ee");
        List<Map<String, Object>> run = rows(a, "exposure-run");
        if (nodes == null || ees == null || run == null || run.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure tree on " + date + missing(a));
        }
        Map<String, Map<String, Object>> byScope = new LinkedHashMap<>();
        nodes.forEach(r -> byScope.put(String.valueOf(r.get("SCOPE")), r));
        Map<String, List<Long>> ee = new LinkedHashMap<>();
        ees.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("POINT")).longValue()));
        ees.forEach(r -> ee.computeIfAbsent(String.valueOf(r.get("SCOPE")), x -> new ArrayList<>()).add(Math.round(((Number) r.get("EE")).doubleValue())));
        // the limit's use is the peak PFE at the limit's confidence when the figures hold it (the configured confidences and the tree's own), else the tree's
        double lq = es.props().limitConfidence();
        boolean other = Math.abs(lq - ask.q()) >= 1e-12 && java.util.Arrays.stream(es.props().confidences()).anyMatch(c -> Math.abs(c - lq) < 1e-12);
        Map<String, Double> atLimit = new LinkedHashMap<>();
        if (other) {
            Map<String, Object> lp = new LinkedHashMap<>(prm);
            lp.put("confidence", lq);
            List<Map<String, Object>> cs = rows(call(p, List.of("counterparty-summary"), lp, date), "counterparty-summary");
            for (Map<String, Object> r : cs == null ? List.<Map<String, Object>>of() : cs) {
                atLimit.put(String.valueOf(r.get("COUNTERPARTY")), ((Number) r.get("PEAK_PFE")).doubleValue());
            }
        }
        String runId = String.valueOf(run.get(0).get("RUN_ID"));
        ExposureViews views = this.views.getObject();
        // the tree's shape from the reference alone (no paths read): the netting sets with paths and their order are the engine's rows
        ExposureLoader.ExposureReference ref = es.loader().reference(date);
        List<Map<String, Object>> sets = new ArrayList<>(nodes.stream().filter(r -> "netting-set".equals(r.get("KIND"))).toList());
        sets.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("ORDER")).longValue()));
        List<String> setIds = sets.stream().map(r -> String.valueOf(r.get("ID"))).toList();
        Map<String, Integer> ordinal = new LinkedHashMap<>();
        for (int i = 0; i < setIds.size(); i++) {
            ordinal.put(setIds.get(i), i);
        }
        List<SetRef> refs = new ArrayList<>();
        for (Map<String, Object> r : sets) {
            String id = String.valueOf(r.get("ID"));
            ExposureDate.Known k = ref.date().known().get(id);
            refs.add(new SetRef(id, k == null ? null : k.counterparty(), k == null ? null : k.legalEntity(), ref.flags().getOrDefault(id, List.of()),
                    ((Number) r.get("TRADES")).intValue()));
        }
        ExposureViews.TreeShape shape = new ExposureViews.TreeShape() {
            @Override
            public ExposureDate date() {
                return ref.date();
            }

            @Override
            public int set(String id) {
                return ordinal.getOrDefault(id, -1);
            }

            @Override
            public boolean sees(int set) {
                return true;                                            // a viewer with access levels is refused above: the rest see every set
            }

            @Override
            public boolean details(int set) {
                return true;
            }

            @Override
            public boolean full() {
                return true;
            }

            @Override
            public String csa(int set) {
                return ExposureExplorer.csaLabel(ref.terms().getOrDefault(setIds.get(set), com.ash.drishti.banking.compute.ccr.CsaTerms.NONE));
            }

            @Override
            public String home(String counterparty) {                   // as ExposureExplorer.homeLegalEntity: the legal entity with most trades
                Map<String, Integer> n = new java.util.HashMap<>();
                for (SetRef sr : refs) {
                    if (counterparty.equals(sr.counterparty()) && sr.legalEntity() != null) {
                        n.merge(sr.legalEntity(), sr.trades(), Integer::sum);
                    }
                }
                return n.entrySet().stream().max((x, y) -> x.getValue().equals(y.getValue()) ? y.getKey().compareTo(x.getKey())
                        : Integer.compare(x.getValue(), y.getValue())).map(Map.Entry::getKey).orElse(null);
            }

            @Override
            public List<String> tenors() {
                return ExposureExplorer.tenors(ref.grid());
            }

            @Override
            public int withPaths() {
                return setIds.size();
            }
        };
        ExposureViews.TreeFigures figures = new ExposureViews.TreeFigures() {
            @Override
            public int put(Map<String, Object> n, String scope, ExposureDate d) {
                Map<String, Object> r = byScope.get(scope);
                if (r == null) {
                    throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + date);
                }
                if (r.get("NOTE") != null) {                              // as the in-tree route words a scope too large to answer interactively
                    throw new DrishtiException(ErrorCode.EXPOSURE_BATCH_JOB, r.get("NOTE") + ": its official figures are computed by the end-of-day "
                            + "capture (GET /api/v1/risk/exposure/official), or narrow the scope");
                }
                n.put("epe", Math.round(((Number) r.get("EPE")).doubleValue()));
                n.put("peakPfe", Math.round(((Number) r.get("PEAK_PFE")).doubleValue()));
                n.put("peakPfeTenor", r.get("PEAK_PFE_TENOR"));
                n.put("peakEe", Math.round(((Number) r.get("PEAK_EE")).doubleValue()));
                n.put("ee", ee.getOrDefault(scope, List.of()));
                List<String> flags = flags(r.get("FLAGS"), refs, ref.date().groups(), scope);
                if (!d.warnings().isEmpty() && !flags.contains("stale-run")) {    // the host's word on its own run, as in-tree
                    flags.add("stale-run");
                }
                n.put("flags", flags);
                return ((Number) r.get("NETTING_SETS")).intValue();
            }

            @Override
            public Map<String, Object> limit(ExposureDate d, String counterparty) {
                Map<String, Object> r = byScope.get("CPTY:" + counterparty);
                double used = other ? atLimit.getOrDefault(counterparty, Double.NaN) : ((Number) r.get("PEAK_PFE")).doubleValue();
                return views.limit(d, counterparty, used);
            }

            @Override
            public String runId(ExposureDate d) {
                return runId;
            }
        };
        return views.tree(p, shape, ExposureService.Ask.of(es.props(), q, null, null, null), figures);
    }

    /** A netting set with paths as a scope's flags read it: its id, counterparty, legal entity, data-quality flags and trades with paths. */
    record SetRef(String id, String counterparty, String legalEntity, List<String> flags, int trades) {}

    /**
     * A netting set's or counterparty's page figures through the engine ({@link ExposureFigures}), as the in-tree scope answer's keys they
     * read: {@code canonical}, {@code businessDate}, {@code runId}, {@code paths} ({@code exposure-run}), {@code confidence}, {@code ce},
     * {@code epe}, {@code eepe}, {@code peakEe}, {@code peakPfe}, {@code peakPfeDate}, {@code peakPfe99}, {@code profile} (tenor, date, ee,
     * ene, pfe, pfe99), {@code flags} ({@code exposure-flags}); at the route's default confidence and collateral model, and at 99% when the
     * configured confidences hold it. A scope whose netting sets the run does not cover is said so in the in-tree route's words.
     */
    Map<String, Object> figures(Principal p, String scope, LocalDate date) {
        ExposureService es = svc.getObject();
        LocalDate latest = es.loader().latest();
        LocalDate d = date == null || latest != null && date.isAfter(latest) ? latest : date;   // as the in-tree route reads the date
        if (d == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths in any connector (kind " + es.props().kinds().paths() + ")");
        }
        ExposureService.Ask ask = ExposureService.Ask.of(es.props(), null, null, null, null);
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("scope", scope);
        prm.put("standalone", false);
        prm.put("confidence", ask.q());
        prm.put("collateral", ask.collateral());
        Map<String, Object> r0 = call(p, List.of("scope-resolution", "exposure-run"), Map.of("scope", scope), d);
        List<Map<String, Object>> res = rows(r0, "scope-resolution");
        List<Map<String, Object>> run = rows(r0, "exposure-run");
        if (res == null || res.isEmpty() || run == null || run.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + d + missing(r0));
        }
        String runId = String.valueOf(run.get(0).get("RUN_ID"));
        if (((Number) res.get(0).get("TRADES")).longValue() == 0) {
            String id = scope.substring(scope.indexOf(':') + 1).trim().toUpperCase(Locale.ROOT);
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, scope.regionMatches(true, 0, "NSET:", 0, 5)
                    ? "no exposure paths for the netting set " + id + " on " + d + " (the run " + runId + " does not cover it)"
                    : "no exposure paths for the counterparty " + id + " on " + d + " (the run " + runId + " covers none of its netting sets)");
        }
        Map<String, Object> a = call(p, List.of("exposure-summary", "exposure-profile", "exposure-flags", "exposure-by-netting-set"), prm, d);
        List<Map<String, Object>> summary = rows(a, "exposure-summary");
        List<Map<String, Object>> points = rows(a, "exposure-profile");
        List<Map<String, Object>> flags = rows(a, "exposure-flags");
        List<Map<String, Object>> bySet = rows(a, "exposure-by-netting-set");
        if (summary == null || summary.isEmpty() || points == null || flags == null || flags.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + d + missing(a));
        }
        // the 99% figures when the in-tree calculation holds that confidence (the configured ones and the asked one)
        boolean has99 = Math.abs(ask.q() - 0.99) < 1e-12 || java.util.Arrays.stream(es.props().confidences()).anyMatch(c -> Math.abs(c - 0.99) < 1e-12);
        List<Map<String, Object>> s99 = null;
        List<Map<String, Object>> p99 = null;
        if (has99) {
            Map<String, Object> prm99 = new LinkedHashMap<>(prm);
            prm99.put("confidence", 0.99);
            Map<String, Object> b = call(p, List.of("exposure-summary", "exposure-profile"), prm99, d);
            s99 = rows(b, "exposure-summary");
            p99 = rows(b, "exposure-profile");
        }
        Map<String, Object> s = summary.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", res.get(0).get("CANONICAL"));
        out.put("businessDate", d.toString());
        out.put("runId", runId);
        out.put("paths", ((Number) run.get(0).get("PATHS")).intValue());
        out.put("confidence", ask.q());
        for (String[] f : FIGURES) {
            out.put(f[1], Math.round(((Number) s.get(f[0])).doubleValue()));
        }
        out.put("peakPfeDate", s.get("PEAK_PFE_DATE") == null ? null : s.get("PEAK_PFE_DATE").toString());
        out.put("peakPfe99", s99 == null || s99.isEmpty() ? null : Math.round(((Number) s99.get(0).get("PEAK_PFE")).doubleValue()));
        List<Map<String, Object>> prof = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Map<String, Object> r = points.get(i);
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("tenor", r.get("TENOR"));
            x.put("date", String.valueOf(r.get("DATE")));
            x.put("ee", Math.round(((Number) r.get("EE")).doubleValue()));
            x.put("ene", Math.round(((Number) r.get("ENE")).doubleValue()));
            x.put("pfe", Math.round(((Number) r.get("PFE")).doubleValue()));
            x.put("pfe99", p99 == null || i >= p99.size() ? null : Math.round(((Number) p99.get(i).get("PFE")).doubleValue()));
            prof.add(x);
        }
        out.put("profile", prof);
        // the data-quality flags of the scope's netting sets (in the book's order) from the reference alone: no paths read here (R2)
        ExposureLoader.ExposureReference ref = es.loader().reference(d);
        List<SetRef> sets = new ArrayList<>();
        List<Map<String, Object>> ordered = new ArrayList<>(bySet == null ? List.of() : bySet);
        ordered.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("RANK")).longValue()));
        for (Map<String, Object> r : ordered) {
            String id = String.valueOf(r.get("NETTING_SET"));
            ExposureDate.Known k = ref.date().known().get(id);
            sets.add(new SetRef(id, k == null ? String.valueOf(r.get("COUNTERPARTY")) : k.counterparty(), k == null ? null : k.legalEntity(),
                    ref.flags().getOrDefault(id, List.of()), 0));
        }
        out.put("flags", flags(flags.get(0).get("FLAGS"), sets, ref.date().groups(), scope));
        out.put("path", "engine");
        return out;
    }

    /**
     * The audit line of an engine-path read, as the in-tree route writes it ({@code ccr-view}, the canonical scope; date, run, view,
     * confidence, collateral model, EPE, peak PFE, netting sets, trades, time and what was read), with {@code version=engine} (the host
     * loaded no date of its own) and {@code path=engine}; the hidden netting sets are the engine's (the viewer's book), not counted here.
     */
    private static void audit(ExposureService es, Principal p, Map<String, Object> a, LocalDate date, Measures m, String extra, long t0) {
        if (es == null) {
            return;
        }
        try {
            Map<String, Object> s = rows(a, "exposure-summary").get(0);
            Map<String, Object> f = rows(a, "exposure-flags").get(0);
            Map<String, Object> run = rows(a, "exposure-run").get(0);
            ExposureService.Ask ask = ExposureService.Ask.of(es.props(), m.q(), m.collateral(), null, null);
            es.audit(p == null ? ExposureOfficialStore.OWNER : p.user(), "ccr-view", String.valueOf(rows(a, "scope-resolution").get(0).get("CANONICAL")),
                    String.format(Locale.ROOT, "date=%s run=%s version=engine view=%s q=%s collateral=%s epe=%.0f peakPfe=%.0f sets=%d trades=%d "
                            + "ms=%d %s path=engine", date, run.get("RUN_ID"), p != null && es.access().seesAll(p) ? "all" : "restricted", ask.q(),
                            ask.collateral(), ((Number) s.get("EPE")).doubleValue(), ((Number) s.get("PEAK_PFE")).doubleValue(),
                            ((Number) f.get("NETTING_SETS")).longValue(), ((Number) s.get("TRADES")).longValue(), (System.nanoTime() - t0) / 1_000_000, extra));
        } catch (RuntimeException e) {
            // best effort for reads, as in-tree
        }
    }

    /**
     * A node's flags: the engine's ({@code partial}, {@code standalone}), then the data-quality flags of the scope's netting sets
     * ({@code PAST_MATURITY}, {@code MPOR_MISMATCH}: the host's reference data, its trades' status and its CSA terms against the run's
     * look-back), each once, in the netting sets' order, as the in-tree calculation adds them. {@code seen}: the netting sets the engine's
     * book holds for the viewer.
     */
    static List<String> flags(Object engine, ExposureDate d, String scope, java.util.function.Predicate<String> seen) {
        List<SetRef> sets = new ArrayList<>();
        for (com.ash.drishti.banking.compute.ccr.ExposureBook.SetInfo si : d.book().sets()) {
            if (seen.test(si.id())) {
                sets.add(new SetRef(si.id(), si.counterparty(), si.legalEntity(), si.flags(), si.to() - si.from()));
            }
        }
        return flags(engine, sets, d.groups(), scope);
    }

    /** The same over netting sets given in the book's order (the reference-only read's: the engine's rows with the host's reference flags). */
    static List<String> flags(Object engine, List<SetRef> sets, Map<String, List<String>> groups, String scope) {
        List<String> out = new ArrayList<>();
        for (String f : String.valueOf(engine == null ? "" : engine).split(",")) {
            if (!f.isBlank()) {
                out.add(f);
            }
        }
        String s = scope.trim();
        String id = s.contains(":") ? s.substring(s.indexOf(':') + 1).trim() : s;
        for (SetRef si : sets) {
            boolean in = s.regionMatches(true, 0, "NSET:", 0, 5) ? si.id().equalsIgnoreCase(id)
                    : s.regionMatches(true, 0, "CPTY:", 0, 5) ? id.equalsIgnoreCase(si.counterparty())
                    : s.regionMatches(true, 0, "LE:", 0, 3) ? id.equalsIgnoreCase(si.legalEntity())
                    : s.regionMatches(true, 0, "GROUP:", 0, 6) ? members(groups, id).contains(si.counterparty())
                    : s.equalsIgnoreCase("BANK");
            if (!in) {
                continue;
            }
            for (String f : si.flags()) {
                if (!out.contains(f) && !f.equals("standalone")) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** A connected group's members as the in-tree scope words read them (the id, else {@code GRP-<id>}). */
    private static List<String> members(Map<String, List<String>> groups, String group) {
        List<String> m = groups.get(group);
        return m != null ? m : groups.getOrDefault("GRP-" + group, List.of());
    }

    /**
     * The figures of an official record through the engine ({@code exposure-official}, {@code exposure-official-profile},
     * {@code exposure-trade-ee}), as {@link ExposureOfficial} computes them in-tree, for every scope of the record in one ask: each scope's
     * figures in whole units (CE, EPE, EEPE, peak EE, peak PFE at 95% and 99%, the peak's date at the limit's confidence, the IMM EAD, PFE
     * 95% and EE at 1Y and 5Y, the profile, netting sets, partial sets, trades, flags with the host's data-quality flags) and every trade's
     * EE contribution. The record's structure, versions, store and published tables stay the host's; asked as the server (no viewer).
     */
    ExposureOfficial.Figures officialFigures(ExposureDate d, ExposureService.Ask ask, List<String> scopes) {
        ExposureService es = svc.getObject();
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("scopes", scopes);
        prm.put("confidence", ask.q());
        prm.put("collateral", ask.collateral());
        List<Map<String, Object>> groups = new ArrayList<>();
        d.groups().forEach((g, ms) -> ms.forEach(c -> groups.add(new LinkedHashMap<>(Map.of("GROUP", g, "COUNTERPARTY", c)))));
        if (!groups.isEmpty()) {
            prm.put("groups", groups);
        }
        // a scope only the host resolves (a hierarchy's node over the trades' level columns): its trades, as the engine's scope-members rows;
        // the netting sets it touches give its data-quality flags
        List<Map<String, Object>> members = new ArrayList<>();
        Map<String, java.util.Set<String>> setsOf = new LinkedHashMap<>();
        com.ash.drishti.banking.compute.ccr.ExposureScopes words = new com.ash.drishti.banking.compute.ccr.ExposureScopes(d.book(),
                gr -> java.util.Optional.ofNullable(d.groups().get(gr)), es.loader().hierarchySpecs());
        for (String sc : scopes) {
            if (sc.equalsIgnoreCase("BANK") || java.util.regex.Pattern.compile("^(NSET|CPTY|GROUP|LE):[^ +&-]+$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(sc).matches()) {
                continue;
            }
            java.util.Set<String> sets = new java.util.LinkedHashSet<>();
            words.resolve(sc).trades().forEach(t -> {
                com.ash.drishti.banking.compute.ccr.ExposureBook.TradeInfo ti = d.book().trade(t);
                members.add(new LinkedHashMap<>(Map.of("SCOPE", sc, "TRADE_ID", ti.id())));
                sets.add(d.book().set(ti.set()).id());
            });
            setsOf.put(sc, sets);
        }
        if (!members.isEmpty()) {
            prm.put("scope-members", members);
        }
        Principal server = new Principal(ExposureOfficialStore.OWNER, List.of("*"));
        Map<String, Object> a = call(server, List.of("exposure-official", "exposure-official-profile"), prm, d.date());
        Map<String, Object> tp = new LinkedHashMap<>();
        tp.put("confidence", ask.q());
        tp.put("collateral", ask.collateral());
        Map<String, Object> ta = call(server, List.of("exposure-trade-ee"), tp, d.date());
        List<Map<String, Object>> rows = rows(a, "exposure-official");
        List<Map<String, Object>> prof = rows(a, "exposure-official-profile");
        List<Map<String, Object>> tee = rows(ta, "exposure-trade-ee");
        if (rows == null || prof == null || tee == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no official figures of " + d.date() + missing(a) + missing(ta));
        }
        Map<String, Map<String, Object>> byScope = new LinkedHashMap<>();
        rows.forEach(r -> byScope.put(String.valueOf(r.get("SCOPE")), r));
        Map<String, List<Map<String, Object>>> profiles = new LinkedHashMap<>();
        prof.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("POINT")).longValue()));
        prof.forEach(r -> profiles.computeIfAbsent(String.valueOf(r.get("SCOPE")), x -> new ArrayList<>()).add(r));
        double alpha = es.props().alpha();
        // every trade's EE contribution, in the engine's order (the book's netting sets, each set's trades in the calculation's order)
        tee.sort(java.util.Comparator.<Map<String, Object>>comparingLong(r -> ((Number) r.get("ORDER")).longValue())
                .thenComparingLong(r -> ((Number) r.get("POINT")).longValue()));
        Map<String, Object> trades = new LinkedHashMap<>();
        List<String> tenors = new ArrayList<>();
        com.ash.drishti.banking.compute.ccr.ExposureGrid g = d.book().grid();
        for (int i = 0; i < g.primaryCount(); i++) {
            tenors.add(g.tenor(g.primaryAt(i)));
        }
        trades.put("businessDate", d.date().toString());
        trades.put("runId", d.book().runId());
        trades.put("tenors", tenors);
        Map<String, Object> byTrade = new LinkedHashMap<>();
        Map<String, List<Long>> ees = new LinkedHashMap<>();
        Map<String, String> setOf = new LinkedHashMap<>();
        for (Map<String, Object> r : tee) {
            String t = String.valueOf(r.get("TRADE_ID"));
            ees.computeIfAbsent(t, x -> new ArrayList<>()).add(Math.round(((Number) r.get("EE")).doubleValue()));
            setOf.put(t, String.valueOf(r.get("NETTING_SET")));
        }
        ees.forEach((t, ee) -> byTrade.put(t, Map.of("nettingSet", setOf.get(t), "ee", ee)));
        trades.put("trades", byTrade);
        return new ExposureOfficial.Figures() {
            @Override
            public Map<String, Object> of(String scope) {
                Map<String, Object> r = byScope.get(scope);
                if (r == null || r.get("NOTE") != null) {
                    throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no official figures of " + scope + " on "
                            + d.date() + (r == null ? "" : ": " + r.get("NOTE")));
                }
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("ce", Math.round(num(r.get("CE"))));
                f.put("epe", Math.round(num(r.get("EPE"))));
                f.put("eepe", Math.round(num(r.get("EEPE"))));
                f.put("peakEe", Math.round(num(r.get("PEAK_EE"))));
                f.put("peakPfe95", r.get("PEAK_PFE_95") == null ? null : Math.round(num(r.get("PEAK_PFE_95"))));
                f.put("peakPfe99", r.get("PEAK_PFE_99") == null ? null : Math.round(num(r.get("PEAK_PFE_99"))));
                f.put("peakPfeDate", r.get("PEAK_PFE_DATE"));
                f.put("eadImm", Math.round(alpha * num(r.get("EEPE"))));
                List<Map<String, Object>> pr = profiles.getOrDefault(scope, List.of());
                f.put("pfe95At1Y", at(pr, "1Y", "PFE_95"));
                f.put("pfe95At5Y", at(pr, "5Y", "PFE_95"));
                f.put("eeAt1Y", at(pr, "1Y", "EE"));
                f.put("eeAt5Y", at(pr, "5Y", "EE"));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> x : pr) {
                    Map<String, Object> y = new LinkedHashMap<>();
                    y.put("TENOR", x.get("TENOR"));
                    y.put("DATE", String.valueOf(x.get("DATE")));
                    y.put("EE", Math.round(num(x.get("EE"))));
                    y.put("ENE", Math.round(num(x.get("ENE"))));
                    y.put("EEE", Math.round(num(x.get("EEE"))));
                    y.put("PFE_95", x.get("PFE_95") == null ? null : Math.round(num(x.get("PFE_95"))));
                    y.put("PFE_99", x.get("PFE_99") == null ? null : Math.round(num(x.get("PFE_99"))));
                    out.add(y);
                }
                f.put("profile", out);
                f.put("nettingSets", ((Number) r.get("NETTING_SETS")).intValue());
                f.put("partialSets", ((Number) r.get("PARTIAL_SETS")).intValue());
                f.put("trades", ((Number) r.get("TRADES")).intValue());
                java.util.Set<String> touched = setsOf.get(scope);
                f.put("flags", touched == null ? flags(r.get("FLAGS"), d, scope, set -> true) : flags(r.get("FLAGS"), d, "BANK", touched::contains));
                return f;
            }

            @Override
            public Map<String, Object> trades() {
                return trades;
            }
        };
    }

    /**
     * A scope's figures through the engine for a capture or the compare's "now" (decision 6.6 (6) as for VaR: the capture's own fields are the
     * host's, its figures the path's): over the host's answer {@code head} (its kind, label, run, provenance, the grid's dates and times), the
     * engine's CE, EPE, EEPE, peak EE, peak PFE with its date and tenor, peak PFE at 99%, the IMM EAD, the profile's figures (EE, ENE, effective
     * EE, PFE at the asked confidence, at 95% and 99%, uncollateralised EE) at every primary date, each netting set's figures
     * ({@code exposure-by-netting-set}) and the flags; {@code figuresFrom: engine}. Refused in words: a viewer whose role has exposure
     * access levels (answered in-tree).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> overlay(Principal p, String scope, LocalDate date, ExposureService.Ask ask, Map<String, Object> head) {
        ExposureService es = svc.getObject();
        if (p != null && !es.access().levels(p).isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "your role's exposure access levels are applied in-tree; captures are made "
                    + "there (path=in-tree)");
        }
        LocalDate d = LocalDate.parse(String.valueOf(head.get("businessDate")));
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("scope", scope);
        prm.put("standalone", false);
        prm.put("confidence", ask.q());
        prm.put("collateral", ask.collateral());
        groups(scope, d, prm);
        Map<String, Object> a = call(p, List.of("exposure-summary", "exposure-profile", "exposure-flags", "exposure-by-netting-set", "scope-resolution"),
                prm, d);
        Map<String, Map<String, Object>> at = new LinkedHashMap<>();         // the summary and profile at 95% and 99% (the profile's PFE columns)
        Map<String, List<Map<String, Object>>> prof = new LinkedHashMap<>();
        for (double q : new double[] {0.95, 0.99}) {
            Map<String, Object> pq = new LinkedHashMap<>(prm);
            pq.put("confidence", q);
            Map<String, Object> aq = call(p, List.of("exposure-summary", "exposure-profile"), pq, d);
            prof.put(q == 0.95 ? "pfe95" : "pfe99", rows(aq, "exposure-profile"));
            at.put(q == 0.95 ? "s95" : "s99", rows(aq, "exposure-summary").get(0));
        }
        List<Map<String, Object>> summary = rows(a, "exposure-summary");
        List<Map<String, Object>> profile = rows(a, "exposure-profile");
        if (summary == null || summary.isEmpty() || profile == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + d + missing(a));
        }
        Map<String, Object> s = summary.get(0);
        Map<String, Object> out = new LinkedHashMap<>(head);
        out.put("ce", Math.round(num(s.get("CE"))));
        out.put("epe", Math.round(num(s.get("EPE"))));
        out.put("eepe", Math.round(num(s.get("EEPE"))));
        out.put("peakEe", Math.round(num(s.get("PEAK_EE"))));
        out.put("peakPfe", Math.round(num(s.get("PEAK_PFE"))));
        Object peakDate = s.get("PEAK_PFE_DATE");
        profile.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("POINT")).longValue()));
        out.put("peakPfeDate", peakDate == null ? null : peakDate.toString());
        out.put("peakPfeTenor", peakDate == null ? null : profile.stream().filter(r -> peakDate.toString().equals(String.valueOf(r.get("DATE"))))
                .map(r -> r.get("TENOR")).findFirst().orElse(null));
        boolean has99 = java.util.Arrays.stream(es.props().confidences()).anyMatch(c -> Math.abs(c - 0.99) < 1e-12) || Math.abs(ask.q() - 0.99) < 1e-12;
        out.put("peakPfe99", has99 ? Math.round(num(at.get("s99").get("PEAK_PFE"))) : null);
        out.put("eadImm", Math.round(es.props().alpha() * num(s.get("EEPE"))));
        boolean has95 = java.util.Arrays.stream(es.props().confidences()).anyMatch(c -> Math.abs(c - 0.95) < 1e-12) || Math.abs(ask.q() - 0.95) < 1e-12;
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> hp = head.get("profile") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        for (Map<String, Object> h : hp) {                               // the host's grid rows (dates, times, roles), the engine's figures
            Map<String, Object> r = new LinkedHashMap<>(h);
            Map<String, Object> e = byDate(profile, h.get("date"));
            if (e != null && "PRIMARY".equals(h.get("role"))) {
                r.put("ee", Math.round(num(e.get("EE"))));
                r.put("ene", Math.round(num(e.get("ENE"))));
                r.put("eee", Math.round(num(e.get("EEE"))));
                r.put("pfe", Math.round(num(e.get("PFE"))));
                Map<String, Object> e95 = byDate(prof.get("pfe95"), h.get("date"));
                Map<String, Object> e99 = byDate(prof.get("pfe99"), h.get("date"));
                r.put("pfe95", has95 && e95 != null ? Math.round(num(e95.get("PFE"))) : null);
                r.put("pfe99", has99 && e99 != null ? Math.round(num(e99.get("PFE"))) : null);
                r.put("eeUncoll", Math.round(num(e.get("EE_UNCOLL"))));
            }
            rows.add(r);
        }
        out.put("profile", rows);
        List<Map<String, Object>> sets = new ArrayList<>();
        List<Map<String, Object>> by = rows(a, "exposure-by-netting-set");
        String kind = String.valueOf(rows(a, "scope-resolution").get(0).get("KIND"));
        long nsets = ((Number) rows(a, "exposure-flags").get(0).get("NETTING_SETS")).longValue();
        if (by != null && (nsets >= 2 || List.of("counterparty", "group", "legal-entity", "bank").contains(kind))) {
            Map<String, String> labels = new LinkedHashMap<>();
            if (head.get("byNettingSet") instanceof List<?> hb) {           // the label is the host's (the counterparty's name as the viewer sees it)
                for (Object o : hb) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    labels.put(String.valueOf(m.get("nettingSet")), String.valueOf(m.get("label")));
                }
            }
            by.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("RANK")).longValue()));
            for (Map<String, Object> r : by) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("nettingSet", r.get("NETTING_SET"));
                x.put("counterparty", r.get("COUNTERPARTY"));
                x.put("label", labels.getOrDefault(String.valueOf(r.get("NETTING_SET")), String.valueOf(r.get("NETTING_SET"))));
                x.put("partial", r.get("PARTIAL"));
                x.put("epe", Math.round(num(r.get("EPE"))));
                x.put("peakEe", Math.round(num(r.get("PEAK_EE"))));
                x.put("peakPfe", Math.round(num(r.get("PEAK_PFE"))));
                x.put("pfeContribution", Math.round(num(r.get("PFE_CONTRIBUTION"))));
                sets.add(x);
            }
            sets.sort((x, y) -> Long.compare((Long) y.get("pfeContribution"), (Long) x.get("pfeContribution")));
        }
        out.put("byNettingSet", sets);
        ExposureDate hd = es.date(d);
        List<String> flags = flags(rows(a, "exposure-flags").get(0).get("FLAGS"), hd, scope, set -> true);
        if (!hd.warnings().isEmpty() && !flags.contains("stale-run")) {
            flags.add("stale-run");
        }
        out.put("flags", flags);
        out.put("figuresFrom", "engine");
        return out;
    }

    private static Map<String, Object> byDate(List<Map<String, Object>> rows, Object date) {
        for (Map<String, Object> r : rows == null ? List.<Map<String, Object>>of() : rows) {
            if (String.valueOf(r.get("DATE")).equals(String.valueOf(date))) {
                return r;
            }
        }
        return null;
    }

    private static Long at(List<Map<String, Object>> profile, String tenor, String column) {
        for (Map<String, Object> x : profile) {
            if (tenor.equals(x.get("TENOR"))) {
                return x.get(column) == null ? null : Math.round(num(x.get(column)));
            }
        }
        return null;
    }

    /** The quantiles of a distribution (the in-tree route's) and of its path fan. */
    private static final List<Double> QUANTILES = List.of(0.5, 0.9, 0.95, 0.975, 0.99);
    private static final double[] FAN = {0.05, 0.25, 0.5, 0.75, 0.95};

    /**
     * A scope's exposure distribution at a primary date through the engine ({@code exposure-distribution}, {@code exposure-histogram},
     * {@code exposure-fan}), as the in-tree {@code /scope/distribution} answers it: {@code scope}, {@code canonical}, {@code businessDate},
     * {@code date}, {@code tenor}, {@code histogram} (edges in whole units, counts), {@code quantiles} (50 to 99%), {@code mean},
     * {@code zeroShare}, {@code paths}, with {@code bands} the path fan (5 to 95% at every primary date), {@code path: engine}; the audit line
     * as in-tree. {@code at}: a primary date or tenor, else the peak PFE's date.
     */
    Map<String, Object> distribution(Principal p, String scope, LocalDate date, Measures m, String at, int bins, boolean bands) {
        long t0 = System.nanoTime();
        if (date == null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path needs the business date: give asOf");
        }
        ExposureService es = svc.getObject();
        int i = -1;
        if (at != null && !at.isBlank()) {
            List<Map<String, Object>> pts = rows(ask(p, scope, date, m, List.of("exposure-profile")), "exposure-profile");
            if (pts == null || pts.isEmpty()) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure profile of " + scope + " on " + date);
            }
            i = primary(pts, at);
        }
        String s = scope == null ? "" : scope.trim();
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("scope", s.isEmpty() ? "BANK" : s);
        prm.put("standalone", false);
        prm.putAll(m.params());
        groups(s, date, prm);
        prm.put("at", i);
        prm.put("bins", bins <= 0 ? es.props().histogramBins() : Math.min(500, bins));
        List<String> outs = new ArrayList<>(List.of("scope-resolution", "exposure-summary", "exposure-flags", "exposure-run", "exposure-distribution",
                "exposure-histogram"));
        outs.add("exposure-fan");
        Map<String, Object> a = call(p, outs, prm, date);
        List<Map<String, Object>> res = rows(a, "scope-resolution");
        List<Map<String, Object>> dist = rows(a, "exposure-distribution");
        List<Map<String, Object>> hist = rows(a, "exposure-histogram");
        List<Map<String, Object>> fan = rows(a, "exposure-fan");
        if (res == null || res.isEmpty() || dist == null || dist.isEmpty() || hist == null || fan == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure of " + scope + " on " + date + missing(a));
        }
        Map<String, Object> d = dist.get(0);
        int point = ((Number) d.get("POINT")).intValue();
        hist.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("EDGE_INDEX")).longValue()));
        long[] edges = new long[hist.size()];
        int[] counts = new int[hist.size() - 1];
        for (int k = 0; k < hist.size(); k++) {
            edges[k] = Math.round(((Number) hist.get(k).get("EDGE")).doubleValue());
            if (k < counts.length) {
                counts[k] = ((Number) hist.get(k).get("COUNT")).intValue();
            }
        }
        Map<Integer, Map<Double, Map<String, Object>>> byPoint = new java.util.TreeMap<>();
        for (Map<String, Object> r : fan) {
            byPoint.computeIfAbsent(((Number) r.get("POINT")).intValue(), x -> new LinkedHashMap<>()).put(((Number) r.get("QUANTILE")).doubleValue(), r);
        }
        Map<String, Object> q = new LinkedHashMap<>();
        for (double c : QUANTILES) {
            q.put(String.valueOf(c), Math.round(((Number) byPoint.get(point).get(c).get("VALUE")).doubleValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", res.get(0).get("CANONICAL"));
        out.put("businessDate", date.toString());
        out.put("date", String.valueOf(d.get("DATE")));
        out.put("tenor", d.get("TENOR"));
        out.put("histogram", Map.of("edges", edges, "counts", counts));
        out.put("quantiles", q);
        out.put("mean", Math.round(((Number) d.get("MEAN")).doubleValue()));
        out.put("zeroShare", ((Number) d.get("ZERO_SHARE")).doubleValue());
        out.put("paths", ((Number) d.get("PATHS")).intValue());
        if (bands) {
            List<Map<String, Object>> b = new ArrayList<>();
            for (Map<Double, Map<String, Object>> pt : byPoint.values()) {
                Map<String, Object> first = pt.values().iterator().next();
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("tenor", first.get("TENOR"));
                r.put("date", String.valueOf(first.get("DATE")));
                r.put("t", ExposureViews.round4(((Number) first.get("T")).doubleValue()));
                for (double c : FAN) {
                    r.put("q" + Math.round(c * 100), Math.round(((Number) pt.get(c).get("VALUE")).doubleValue()));
                }
                b.add(r);
            }
            out.put("bands", b);
        }
        out.put("path", "engine");
        audit(es, p, a, date, m, "distribution", t0);
        return out;
    }

    /** The primary point of a date or tenor among the profile's rows (as the in-tree route reads it); -1 when none is given. */
    private static int primary(List<Map<String, Object>> points, String date) {
        if (date == null || date.isBlank()) {
            return -1;
        }
        String d = date.trim();
        for (int i = 0; i < points.size(); i++) {
            if (String.valueOf(points.get(i).get("DATE")).equals(d) || d.equalsIgnoreCase(String.valueOf(points.get(i).get("TENOR")))) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "date " + date + " is not a primary grid date of the run (" + points.get(0).get("DATE")
                + " .. " + points.get(points.size() - 1).get("DATE") + ", or a tenor such as 1Y)");
    }

    private static int argmax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[best]) {
                best = i;
            }
        }
        return best;
    }

    /** The netting set a scope is ({@code NSET:<id>}, one set), else null. */
    static String nettingSet(String scope) {
        String s = scope == null ? "" : scope.trim();
        return s.regionMatches(true, 0, "NSET:", 0, 5) && s.indexOf(',') < 0 && s.indexOf(' ') < 0 ? s.substring(5) : null;
    }

    /** The column of netting-set-collateral and the in-tree {@code collateral} key of each CSA term and holding. */
    private static final String[][] TERMS = {{"THRESHOLD_THEM", "thresholdThem"}, {"THRESHOLD_US", "thresholdUs"}, {"MTA_THEM", "mtaThem"},
        {"INDEPENDENT_AMOUNT", "independentAmount"}, {"COLLATERAL_HELD", "heldToday"}, {"IM_HELD", "imHeld"}, {"MTM", "netMtm"}};

    /** The in-tree {@code collateral} block's terms and holdings the engine holds, for one netting set; null when the engine has no row for it. */
    private static Map<String, Object> collateral(Map<String, Object> a, String set) {
        List<Map<String, Object>> t = rows(a, "netting-set-collateral");
        Map<String, Object> r = t == null ? null : t.stream().filter(x -> set.equals(String.valueOf(x.get("NETTING_SET")))).findFirst().orElse(null);
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("csa", r.get("CSA"));
        m.put("margined", r.get("MARGINED"));
        for (String[] f : TERMS) {
            m.put(f[1], Math.round(((Number) r.get(f[0])).doubleValue()));
        }
        m.put("mporDays", r.get("CSA") == null ? null : r.get("MPOR_DAYS"));
        m.put("trades", ((Number) r.get("TRADES")).longValue());
        return m;
    }

    /** A scope's profile at the primary dates through the engine: {@code scope}, {@code businessDate}, {@code profile}, {@code path: engine}. */
    public Map<String, Object> profile(Principal p, String scope, LocalDate date, Measures m) {
        Map<String, Object> a = ask(p, scope, date, m, List.of("exposure-profile"));
        if (rows(a, "exposure-profile") == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no exposure profile of " + scope + " on " + date
                    + missing(a));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("businessDate", date.toString());
        out.put("profile", profileRows(a));
        out.put("path", "engine");
        return out;
    }

    /** The summary column and the route's key of each figure. */
    private static final String[][] FIGURES = {{"CE", "ce"}, {"EPE", "epe"}, {"EEPE", "eepe"}, {"PEAK_EE", "peakEe"}, {"PEAK_PFE", "peakPfe"}};
    /** The profile column and the route's key of each figure. */
    private static final String[][] POINTS = {{"EE", "ee"}, {"ENE", "ene"}, {"EEE", "eee"}, {"PFE", "pfe"}, {"EE_UNCOLL", "eeUncoll"}};

    /** The in-tree profile rows' keys the engine holds ({@code tenor, date, role, ee, ene, eee, pfe, eeUncoll}), each figure's exact double beside. */
    private static List<Map<String, Object>> profileRows(Map<String, Object> a) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> t = rows(a, "exposure-profile");
        for (Map<String, Object> r : t == null ? List.<Map<String, Object>>of() : t) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenor", r.get("TENOR"));
            m.put("date", String.valueOf(r.get("DATE")));
            m.put("role", "PRIMARY");
            for (String[] f : POINTS) {
                double x = ((Number) r.get(f[0])).doubleValue();
                m.put(f[1], Math.round(x));
                m.put(f[1] + "Exact", x);
            }
            rows.add(m);
        }
        return rows;
    }

    private Map<String, Object> ask(Principal p, String scope, LocalDate date, Measures m, List<String> outputs) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path needs the business date: give asOf");
        }
        String s = scope == null ? "" : scope.trim();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scope", s.isEmpty() ? "BANK" : s);
        params.put("standalone", false);
        params.putAll(m.params());
        groups(s, date, params);
        return call(p, outputs, params, date);
    }

    /**
     * A scope naming a connected group ({@code GROUP:<id>}): the date's groups and their member counterparties from the host's reference
     * (the group kind alone, {@link ExposureLoader#groups}), passed as the engine's {@code groups} rows; the engine resolves the scope by
     * Drishti's scope words as in-tree.
     */
    private void groups(String scope, LocalDate date, Map<String, Object> params) {
        if (!scope.toUpperCase(Locale.ROOT).contains("GROUP:")) {
            return;
        }
        ExposureService es = svc.getObject();
        List<Map<String, Object>> rows = new ArrayList<>();
        es.loader().groups(com.ash.drishti.api.AsOf.of(date)).forEach((g, ms) -> ms.forEach(c -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("GROUP", g);
            r.put("COUNTERPARTY", c);
            rows.add(r);
        }));
        if (!rows.isEmpty()) {
            params.put("groups", rows);
        }
    }

    /**
     * A what-if through the engine ({@code exposure-what-if}, {@code exposure-what-if-profile}), as the in-tree {@code POST /what-if} answers
     * it ({@link ExposureWhatIf}: method {@code addon}, the proxy; {@link ExposureWhatIfPaths}: method {@code paths}, exact): the request's
     * candidates as the engine's {@code candidates} rows (a copy of a booked trade, or supplied paths; maturity dates as years from the
     * business date, as in-tree), the figures rounded to whole units as in-tree. Refused in words, answered in-tree: candidates' paths as
     * Arrow, and a viewer whose role has exposure access levels.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> whatIf(Principal p, Map<String, Object> body, LocalDate date, boolean audited) {
        String scope = String.valueOf(body.getOrDefault("scope", "")).trim();
        if (scope.isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a what-if names its scope: {\"scope\": \"CPTY:CP-ALDERSHOT\", ...}");
        }
        if (date == null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path needs the business date: give asOf");
        }
        String method = String.valueOf(body.getOrDefault("method", "addon")).trim().toLowerCase(Locale.ROOT);
        ExposureService es = svc.getObject();
        Object qq = body.get("q");
        Object cc = body.get("collateral");
        ExposureService.Ask ask = ExposureService.Ask.of(es.props(), qq == null ? null : String.valueOf(qq), cc == null ? null : String.valueOf(cc), null, null);
        List<Map<String, Object>> cands = body.get("candidates") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (cands.isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a what-if has candidates: [{tradeId, assetClass, notional, maturityDate, mtm}]");
        }
        if (!method.equals("paths") && !method.equals("addon")) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "method is addon or paths, not " + method);
        }
        if (p != null && !es.access().levels(p).isEmpty()) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "your role's exposure access levels are applied in-tree; the what-if is "
                    + "answered there (path=in-tree)");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : cands) {
            Map<String, Object> r = new LinkedHashMap<>();
            String id = c.get("tradeId") == null ? "CANDIDATE-" + (rows.size() + 1) : String.valueOf(c.get("tradeId"));
            r.put("ID", id);
            r.put("LABEL", c.get("label") == null ? id : String.valueOf(c.get("label")));
            Map<?, ?> pm = c.get("paths") instanceof Map<?, ?> x ? x : null;
            r.put("RUN_ID", pm == null || pm.get("runId") == null ? null : String.valueOf(pm.get("runId")));
            if (method.equals("paths")) {
                if (pm == null) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "method paths needs each candidate's paths on the run: paths: "
                            + "{copyOf: <trade>, factor} or {runId, mtm: [grid date by grid date]}; " + id + " has none");
                }
                if (pm.get("arrow") != null) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path takes a candidate's paths as copyOf or mtm for now; "
                            + "Arrow paths are answered in-tree (path=in-tree)");
                }
                String set = c.get("nettingSet") == null ? "" : String.valueOf(c.get("nettingSet")).trim().toUpperCase(Locale.ROOT);
                r.put("NETTING_SET", set.isEmpty() || set.startsWith("NS-") ? set : "NS-" + set);
                if (pm.get("copyOf") != null) {
                    r.put("COPY_OF", String.valueOf(pm.get("copyOf")).trim());
                    r.put("FACTOR", pm.get("factor") instanceof Number f ? f.doubleValue() : 1.0);
                    r.put("LIFE_YEARS", null);
                } else {
                    r.put("MTM", numbers(pm.get("mtm")));
                    r.put("LIFE_YEARS", years(date, c.get("maturityDate")));
                }
                r.put("MTM0", c.get("mtm") instanceof Number x && Double.isFinite(x.doubleValue()) ? x.doubleValue() : null);
            } else {
                r.put("ASSET_CLASS", String.valueOf(c.getOrDefault("assetClass", "")));
                r.put("NOTIONAL", num(c.get("notional")));
                r.put("DELTA", c.get("delta") == null ? 1.0 : num(c.get("delta")));
                r.put("MTM0", num(c.get("mtm")));
                r.put("LIFE_YEARS", years(date, c.get("maturityDate")));
            }
            rows.add(r);
        }
        List<String> cols = List.of("ID", "LABEL", "RUN_ID", "NETTING_SET", "COPY_OF", "FACTOR", "MTM", "MTM0", "LIFE_YEARS", "ASSET_CLASS", "NOTIONAL", "DELTA");
        for (int i = 0; i < rows.size(); i++) {                         // every row the same columns (the engine's table takes the first row's)
            Map<String, Object> full = new LinkedHashMap<>();
            for (String c : cols) {
                full.put(c, rows.get(i).get(c));
            }
            rows.set(i, full);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scope", scope);
        params.put("confidence", ask.q());
        params.put("collateral", ask.collateral());
        params.put("method", method);
        params.put("candidates", rows);
        groups(scope, date, params);
        long t0 = System.nanoTime();
        Map<String, Object> a = call(p, List.of("exposure-what-if", "exposure-what-if-profile"), params, date);
        List<Map<String, Object>> w = rows(a, "exposure-what-if");
        List<Map<String, Object>> prof = rows(a, "exposure-what-if-profile");
        if (w == null || w.size() < 2 || prof == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no what-if for " + scope + " on " + date + missing(a));
        }
        Map<String, Object> b0 = w.stream().filter(r -> "BEFORE".equals(r.get("ROW"))).findFirst().orElseThrow();   // the engine's rows come by key
        Map<String, Object> b1 = w.stream().filter(r -> "AFTER".equals(r.get("ROW"))).findFirst().orElseThrow();
        prof.sort(java.util.Comparator.comparingLong(r -> ((Number) r.get("POINT")).longValue()));
        boolean exact = method.equals("paths");
        String[][] figs = exact ? new String[][] {{"EPE", "epe"}, {"EEPE", "eepe"}, {"PEAK_PFE", "peakPfe"}, {"PEAK_EE", "peakEe"}, {"CE", "ce"}}
                : new String[][] {{"EPE", "epe"}, {"PEAK_PFE", "peakPfe"}, {"PEAK_EE", "peakEe"}};
        Map<String, Object> fBase = new LinkedHashMap<>();
        Map<String, Object> fWith = new LinkedHashMap<>();
        Map<String, Object> inc = new LinkedHashMap<>();
        for (String[] f : figs) {
            double x0 = ((Number) b0.get(f[0])).doubleValue();
            double x1 = ((Number) b1.get(f[0])).doubleValue();
            fBase.put(f[1], Math.round(x0));
            fWith.put(f[1], Math.round(x1));
            if (exact || !f[1].equals("peakEe")) {
                inc.put(f[1], Math.round(x1 - x0));
            }
        }
        if (exact) {
            fBase.put("peakPfeTenor", b0.get("PEAK_PFE_TENOR"));
            fWith.put("peakPfeTenor", b1.get("PEAK_PFE_TENOR"));
        }
        List<Map<String, Object>> profile = new ArrayList<>();
        for (Map<String, Object> r : prof) {
            Map<String, Object> x = new LinkedHashMap<>();
            double ee0 = ((Number) r.get("EE_BEFORE")).doubleValue();
            double ee1 = ((Number) r.get("EE_AFTER")).doubleValue();
            double pf0 = ((Number) r.get("PFE_BEFORE")).doubleValue();
            double pf1 = ((Number) r.get("PFE_AFTER")).doubleValue();
            x.put("tenor", r.get("TENOR"));
            x.put("date", String.valueOf(r.get("DATE")));
            double t = ((Number) r.get("T")).doubleValue();
            x.put("t", exact ? t : ExposureViews.round4(t));
            x.put("eeBase", Math.round(ee0));
            x.put("ee", Math.round(ee1));
            x.put("pfeBase", Math.round(pf0));
            x.put("pfe", Math.round(pf1));
            if (exact) {
                x.put("incrementalEe", Math.round(ee1 - ee0));
                x.put("incrementalPfe", Math.round(pf1 - pf0));
            } else {
                x.put("incrementalPfe", Math.round(((Number) r.get("ADDED")).doubleValue()));
            }
            profile.add(x);
        }
        inc.put("profile", profile);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", b0.get("CANONICAL"));
        out.put("businessDate", date.toString());
        out.put("runId", b0.get("RUN_ID"));
        out.put("confidence", ask.q());
        out.put("method", method);
        out.put("base", fBase);
        out.put("with", fWith);
        out.put("incremental", inc);
        out.put("candidates", cands.size());
        if (exact) {
            List<Map<String, Object>> items = new ArrayList<>();
            Map<String, Map<String, Object>> byItem = new LinkedHashMap<>();
            w.forEach(r -> byItem.put(String.valueOf(r.get("ROW")), r));
            for (Map<String, Object> cand : rows) {                     // the candidates' order, as in-tree
                Map<String, Object> r = byItem.get("ITEM:" + cand.get("ID"));
                if (r == null) {
                    continue;
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("tradeId", String.valueOf(r.get("ROW")).substring("ITEM:".length()));
                x.put("label", r.get("LABEL"));
                x.put("nettingSet", r.get("NETTING_SET"));
                x.put("peakPfeAlone", Math.round(((Number) r.get("PEAK_PFE_ALONE")).doubleValue()));
                x.put("incrementalPeakPfe", Math.round(((Number) r.get("INCREMENTAL_PEAK_PFE")).doubleValue()));
                items.add(x);
            }
            out.put("items", items);
            out.put("flags", List.of("exact"));
            out.put("note", "exact: each candidate's paths on the run " + b0.get("RUN_ID") + " added to its netting set path by path, collateral ("
                    + com.ash.drishti.banking.compute.ccr.ExposureMath.Model.of(ask.collateral()).text() + ") and exposure computed again by the Java exposure engine");
            out.put("engine", "ccr (Drishti Risk)");
        } else {
            out.put("flags", List.of("proxy"));
            out.put("note", "a conservative proxy: each candidate a netting set of its own, uncollateralised, with SA-CCR's supervisory factor; the "
                    + "exact method is paths (the candidates' paths on the run added to their netting sets)");
        }
        out.put("path", "engine");
        if (audited) {
            es.audit(p == null ? ExposureOfficialStore.OWNER : p.user(), "ccr-whatif", String.valueOf(b0.get("CANONICAL")), String.format(Locale.ROOT,
                    "date=%s run=%s version=engine q=%s collateral=%s epe=%.0f peakPfe=%.0f ms=%d method=%s candidates=%d path=engine", date,
                    b0.get("RUN_ID"), ask.q(), ask.collateral(), ((Number) b0.get("EPE")).doubleValue(), ((Number) b0.get("PEAK_PFE")).doubleValue(),
                    (System.nanoTime() - t0) / 1_000_000, method, cands.size()));
        }
        return out;
    }

    private static List<Double> numbers(Object o) {
        if (!(o instanceof List<?> l)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's paths.mtm is a list of numbers (grid date by grid date)");
        }
        List<Double> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof List<?> row) {
                row.forEach(y -> out.add(finite(y)));
            } else {
                out.add(finite(x));
            }
        }
        return out;
    }

    private static double finite(Object y) {
        if (y instanceof Number n && Double.isFinite(n.doubleValue())) {
            return n.doubleValue();
        }
        throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's path value is a finite number, not " + y);
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's amount is a number, not " + o);
        }
    }

    /** A maturity date as years from the business date (at least a day; absent: one year), as the in-tree what-if reads it. */
    private static double years(LocalDate bd, Object d) {
        if (d == null) {
            return 1;
        }
        try {
            return Math.max(1 / 365.0, java.time.temporal.ChronoUnit.DAYS.between(bd, LocalDate.parse(String.valueOf(d).substring(0, 10))) / 365.0);
        } catch (RuntimeException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "a candidate's maturityDate is a date (YYYY-MM-DD), not " + d);
        }
    }

    private Map<String, Object> call(Principal p, List<String> outputs, Map<String, Object> params, LocalDate date) {
        Engines e = engines.getIfAvailable();
        if (e == null || !e.running(ENGINE)) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " is not running here (drishti-risk-ccr in lib/engines and "
                    + "config/engines/ccr.yaml); the exposure routes answer nothing on the engine path without it");
        }
        try {
            return e.ask(p, ENGINE, outputs, params, Map.of("date", date.toString()));
        } catch (Kernel.Refused r) {
            throw new DrishtiException(r.status() == 403 ? ErrorCode.FORBIDDEN : ErrorCode.ENGINE_BLOCKED, r.getMessage());
        }
    }

    /** A table of the answer as rows by column name (the answer's rows are positional, in the columns' order); null when it is not there. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> answer, String name) {
        for (Object t : (List<Object>) answer.getOrDefault("tables", List.of())) {
            Map<String, Object> tm = (Map<String, Object>) t;
            if (!name.equals(tm.get("name"))) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (Object c : (List<Object>) tm.getOrDefault("columns", List.of())) {
                names.add(String.valueOf(((Map<String, Object>) c).get("name")));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object r : (List<Object>) tm.getOrDefault("rows", List.of())) {
                Object[] cells = r instanceof Object[] x ? x : r instanceof Map<?, ?> ? null : ((List<Object>) r).toArray();
                if (cells == null) {
                    out.add((Map<String, Object>) r);
                    continue;
                }
                Map<String, Object> rm = new LinkedHashMap<>();
                for (int i = 0; i < names.size() && i < cells.length; i++) {
                    rm.put(names.get(i), cells[i]);
                }
                out.add(rm);
            }
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String missing(Map<String, Object> a) {
        List<Object> m = (List<Object>) a.getOrDefault("missing", List.of());
        return m.isEmpty() ? "" : " (not found: " + m + ")";
    }
}
