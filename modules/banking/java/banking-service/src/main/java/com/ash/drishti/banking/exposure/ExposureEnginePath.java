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
 * through the kernel's {@link Engines} service, never by a Risk type. One switch per route family, {@code drishti.module.banking.exposure.path}:
 * {@code in-tree} or {@code engine}; a request may name its path ({@code path=engine} or {@code path=in-tree}) so both answers can be compared
 * on one server. The engine's figures are unrounded; the route rounds them to whole units as the in-tree route does and gives the exact
 * doubles beside them ({@code ...Exact}) for the raw-bit check. What the engine path does not answer yet is refused in one sentence naming
 * {@code path=in-tree}, never answered half.
 */
@Component
public class ExposureEnginePath {

    /** The engine's id (config/engines/ccr.yaml). */
    public static final String ENGINE = "ccr";
    static final String SETTING = "drishti.module.banking.exposure.path";   // engines/exposure.yaml

    private final ObjectProvider<Engines> engines;
    private final Environment env;

    ExposureEnginePath(ObjectProvider<Engines> engines, Environment env) {
        this.engines = engines;
        this.env = env;
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
     * {@code peakEe}, {@code peakPfe} (whole units), {@code peakPfeDate}, and each figure's exact double. The credit limit is the host's
     * reference data: the caller joins it.
     */
    public List<Map<String, Object>> counterparties(Principal p, LocalDate date, Measures m) {
        Map<String, Object> a = ask(p, null, date, m, List.of("counterparty-summary"));
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
            double before = ((Number) r.get("BEFORE")).doubleValue();
            double after = ((Number) r.get("AFTER")).doubleValue();
            lim.put("before", Math.round(before));
            lim.put("after", Math.round(after));
            lim.put("increment", Math.round(after - before));
            lim.put("useBefore", r.get("USE_BEFORE"));
            lim.put("useAfter", r.get("USE_AFTER"));
            List<Map<String, Object>> buckets = new ArrayList<>();
            List<Map<String, Object>> bs = rows(a, "pre-deal-buckets");
            for (Map<String, Object> b : bs == null ? List.<Map<String, Object>>of() : bs) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("bucket", b.get("BUCKET"));
                x.put("limit", Math.round(((Number) b.get("LIMIT")).doubleValue()));
                x.put("before", b.get("BEFORE") instanceof Number n ? Math.round(n.doubleValue()) : null);
                x.put("after", b.get("AFTER") instanceof Number n ? Math.round(n.doubleValue()) : null);
                x.put("useAfter", b.get("USE_AFTER"));
                x.put("status", b.get("STATUS"));
                buckets.add(x);
            }
            lim.put("buckets", buckets);
            lim.put("status", r.get("STATUS"));
        }
        out.put("limit", lim);
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("base", Map.of("ead", Math.round(((Number) r.get("EAD_BEFORE")).doubleValue())));
        exp.put("with", Map.of("ead", Math.round(((Number) r.get("EAD_AFTER")).doubleValue())));
        out.put("exposure", exp);
        out.put("path", "engine");
        return out;
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
        if (s.regionMatches(true, 0, "GROUP:", 0, 6)) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the engine path does not resolve counterparty groups yet; " + s
                    + " is answered in-tree (path=in-tree) until row-set scopes reach the engine path (R2 step 4b)");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scope", s.isEmpty() ? "BANK" : s);
        params.put("standalone", false);
        params.putAll(m.params());
        return call(p, outputs, params, date);
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
