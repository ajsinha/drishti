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
package com.ash.drishti.banking.simm;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.banking.compute.simm.SimmChoices;
import com.ash.drishti.banking.frtb.FrtbData;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Engines;
import com.ash.drishti.kernel.api.services.Kernel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * R2 (docs/plans/R2_CUT_PLAN.md, "Switching a route"): {@code GET /api/v1/risk/simm} answered by Drishti Risk's counterparty engine
 * ({@code ccr}: outputs {@code simm}, {@code simm-total}, {@code simm-product}, {@code simm-version}) through the kernel's {@link Engines}
 * service, never by a Risk type. The engine reads the SIMM inputs as lake tables bound in {@code config/engines/ccr.yaml} (the position
 * sensitivities' elements, the factor dictionary, the licensed parameter files as the {@code simm-parameters} kind, the FX mids); the host
 * resolves the scope (a netting set's records as its trade master places them, an analytic scope's records, the viewer's rows: the
 * engine's {@code simm-members}) and keeps the CSA's terms (version, regimes, calculation currency). One switch,
 * {@code drishti.module.banking.simm.route}: {@code in-tree} (default) or {@code engine}; a request may name its {@code path}. What the
 * engine path does not answer yet is refused in one sentence naming {@code path=in-tree}: the hand-built examples, overrides, add-ons.
 */
@Component
public class SimmEnginePath {

    /** The engine's id (config/engines/ccr.yaml). */
    public static final String ENGINE = "ccr";
    static final String SETTING = "drishti.module.banking.simm.route";
    static final List<String> PRODUCT_CLASSES = List.of("RatesFX", "Credit", "Equity", "Commodity");
    static final List<String> RISK_CLASSES = List.of("InterestRate", "CreditQ", "CreditNonQ", "Equity", "Commodity", "FX");
    static final List<String> OUTPUTS = List.of("simm", "simm-total", "simm-product", "simm-version");

    private final ObjectProvider<Engines> engines;
    private final Environment env;

    SimmEnginePath(ObjectProvider<Engines> engines, Environment env) {
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

    /** The headline of {@code GET /simm} (SimmAnswer.result's keys) with the engine's figures; {@code path: engine}. */
    Map<String, Object> result(SimmService svc, Principal p, String scope, LocalDate date, String asked, SimmChoices ch) {
        svc.enabled();
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a netting set (nettingSet=NS-...), a scope (scope=DESK:DESK-RATES, an analytic) or EXAMPLE");
        }
        String stripped = scope.strip();
        final String s = stripped.toUpperCase(Locale.ROOT).startsWith("NS:") ? stripped.substring(3).strip() : stripped;
        if (s.equalsIgnoreCase(SimmService.EXAMPLE)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the hand-built examples read no data: they are answered in-tree (path=in-tree)");
        }
        if (!ch.overrides().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "parameter overrides are answered in-tree for now (path=in-tree)");
        }
        SimmData.Day day = svc.data().day(new AsOf(date, null));
        if (!day.sens().sensitivitiesRead()) {
            throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves position-sensitivity: load the position sensitivities first");
        }
        SimmData.NettingSet ns = day.nettingSets().get(s);
        List<FrtbData.Record> recs = new ArrayList<>();
        String kind;
        String calc;
        String version;
        List<String> collect = List.of("NONE");
        List<String> post = List.of("NONE");
        SimmData.Csa csa = null;
        int missing = 0;
        if (ns != null) {
            Set<String> keep = svc.visible(p, day.date());
            List<FrtbData.Record> all = day.recordsBySet().getOrDefault(ns.id(), List.of());
            if (keep != null && ns.trades().stream().noneMatch(keep::contains) && all.stream().noneMatch(r -> SimmService.seen(keep, r))) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "your role does not see the trades of " + ns.id());
            }
            csa = ns.csa() == null ? null : day.csas().get(ns.csa());
            if (csa != null && csa.model().equals("SCHEDULE")) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, ns.id() + " is margined by schedule IM under " + csa.id() + ": not a SIMM netting set");
            }
            if (csa != null && (csa.fixed() != 0 || !csa.multipliers().isEmpty() || !csa.factors().isEmpty())
                    || !day.addOnLines().getOrDefault(ns.id(), List.of()).isEmpty()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, ns.id() + " has SIMM add-on terms or lines: the add-ons are answered in-tree for now (path=in-tree)");
            }
            for (FrtbData.Record r : all) {
                if (SimmService.seen(keep, r)) {
                    recs.add(r);
                }
            }
            kind = SimmService.NETTING_SET;
            calc = csa == null ? "USD" : csa.calculationCurrency();
            version = asked != null && !asked.isBlank() ? asked : csa == null ? null : csa.version();
            if (csa != null && !csa.collect().isEmpty()) {
                collect = csa.collect();
            }
            if (csa != null && !csa.post().isEmpty()) {
                post = csa.post();
            }
            Set<String> covered = day.tradesWithRecords().getOrDefault(ns.id(), Set.of());
            missing = (int) ns.trades().stream().filter(t -> !covered.contains(t)).count();
        } else {
            recs.addAll(svc.analyticRecords(p, day, s).records());
            kind = SimmService.ANALYTIC;
            calc = "USD";
            version = asked;
        }
        if (version == null || version.isBlank()) {
            version = svc.props().defaultVersion();
        }
        List<Map<String, Object>> members = new ArrayList<>();
        for (FrtbData.Record r : recs) {
            members.add(new LinkedHashMap<>(Map.of("SCOPE", s, "SENSITIVITY_ID", r.id())));
        }
        if (members.isEmpty()) {
            members.add(new LinkedHashMap<>(Map.of("SCOPE", s)));       // the scope with no record: a run of no element, as in-tree
        }
        Map<String, Object> prm = new LinkedHashMap<>();
        prm.put("simm-members", members);
        prm.put("calculation-currency", calc);
        prm.put("simm-version", version == null ? "" : version);
        prm.put("concentration", ch.concentration());
        prm.put("hvr", ch.hvr());
        prm.put("direction", "collect");
        Map<String, Object> c = call(p, prm, day.date());
        prm.put("direction", "post");
        Map<String, Object> po = call(p, prm, day.date());
        Map<String, Object> tc = one(c, "simm-total", s);
        Map<String, Object> tp = one(po, "simm-total", s);
        Map<String, Object> ver = one(c, "simm-version", s);
        List<String> regimes = ver.get("REGIMES") == null || ver.get("REGIMES").toString().isBlank() ? List.of()
                : Arrays.asList(ver.get("REGIMES").toString().split(","));
        String cr = collect.stream().filter(r -> regimes.isEmpty() || regimes.contains(r)).findFirst().orElse("NONE");
        String pr = post.stream().filter(r -> regimes.isEmpty() || regimes.contains(r)).findFirst().orElse("NONE");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("SCOPE", s);
        m.put("KIND", kind);
        m.put("ANALYTIC", !kind.equals(SimmService.NETTING_SET));
        m.put("NETTING_SET", ns == null ? null : ns.id());
        m.put("COUNTERPARTY", ns == null ? null : ns.counterparty());
        m.put("CSA", ns == null ? null : ns.csa());
        m.put("BUSINESS_DATE", day.date() == null ? null : day.date().toString());
        m.put("SIMM_VERSION", ver.get("VERSION"));
        m.put("PARAMETER_SHA256", ver.get("PARAMETER_SHA256"));
        m.put("CALCULATION_CURRENCY", calc);
        m.put("IM_COLLECT", num(tc, "TOTAL"));
        m.put("IM_POST", num(tp, "TOTAL"));
        m.put("COLLECT_REGIME", cr);
        m.put("POST_REGIME", pr);
        m.put("SIMM_COLLECT", num(tc, "SIMM"));
        m.put("ADDON_COLLECT", num(tc, "ADD_ON"));
        m.put("SIMM_POST", num(tp, "SIMM"));
        m.put("ADDON_POST", num(tp, "ADD_ON"));
        Map<String, Double> pcC = byProduct(c, s);
        Map<String, Double> pcP = byProduct(po, s);
        List<Map<String, Object>> pcs = new ArrayList<>();
        for (String pc : PRODUCT_CLASSES) {
            if (!pcC.containsKey(pc) && !pcP.containsKey(pc)) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("PRODUCT_CLASS", pc);
            x.put("COLLECT", pcC.containsKey(pc) ? pcC.get(pc) : (Object) 0);
            x.put("POST", pcP.containsKey(pc) ? pcP.get(pc) : (Object) 0);
            pcs.add(x);
        }
        m.put("BY_PRODUCT_CLASS", pcs);
        List<Map<String, Object>> postRows = rows(po, "simm");
        List<Map<String, Object>> rcs = new ArrayList<>();
        for (Map<String, Object> r : rows(c, "simm")) {
            if (!s.equals(r.get("SCOPE"))) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("PRODUCT_CLASS", r.get("PRODUCT_CLASS"));
            x.put("RISK_CLASS", r.get("RISK_CLASS"));
            x.put("DELTA", num(r, "DELTA"));
            x.put("VEGA", num(r, "VEGA"));
            x.put("CURVATURE", num(r, "CURVATURE"));
            x.put("BASE_CORR", num(r, "BASE_CORR"));
            x.put("TOTAL", num(r, "IM"));
            x.put("POST_TOTAL", postRows.stream().filter(y -> s.equals(y.get("SCOPE")) && r.get("PRODUCT_CLASS").equals(y.get("PRODUCT_CLASS"))
                    && r.get("RISK_CLASS").equals(y.get("RISK_CLASS"))).map(y -> num(y, "IM")).findFirst().orElse(null));
            rcs.add(x);
        }
        // SIMM's order (product classes, then risk classes within each); the in-tree run lists them as its elements first name them
        rcs.sort(java.util.Comparator.comparingInt((Map<String, Object> x) -> PRODUCT_CLASSES.indexOf(String.valueOf(x.get("PRODUCT_CLASS"))))
                .thenComparingInt(x -> RISK_CLASSES.indexOf(String.valueOf(x.get("RISK_CLASS")))));
        m.put("BY_RISK_CLASS", rcs);
        m.put("TRADES", ((Number) tc.get("TRADES")).intValue());
        m.put("MISSING_TRADES", missing);
        m.put("SWITCHES", ch.asMap());
        m.put("OVERRIDES", Map.of());
        boolean regulatory = Boolean.TRUE.equals(tc.get("REGULATORY"));
        m.put("REGULATORY", regulatory && kind.equals(SimmService.NETTING_SET));
        if (!regulatory) {
            m.put("notRegulatory", "not the regulatory figure: " + ch.changed());
        }
        if (!kind.equals(SimmService.NETTING_SET)) {
            m.put("label", SimmService.ANALYTIC);
        }
        m.put("refused", ((Number) tc.get("REFUSED")).intValue());
        Map<String, Object> pv = new LinkedHashMap<>();
        pv.put("Business date", String.valueOf(day.date()));
        pv.put("Records in scope", recs.size());
        pv.put("SIMM version", ver.get("DESCRIPTION"));
        pv.put("Parameter file", "simm-parameters " + ver.get("VERSION") + " · SHA-256 " + ver.get("PARAMETER_SHA256"));
        pv.put("Choices", ch.asMap());
        pv.put("Engine", ENGINE + " (Drishti Risk), through the host");
        m.put("PROVENANCE", pv);
        m.put("path", "engine");
        return m;
    }

    private static Double num(Map<String, Object> r, String c) {
        Object v = r.get(c);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null;
    }

    private static Map<String, Double> byProduct(Map<String, Object> answer, String scope) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(answer, "simm-product")) {
            if (scope.equals(r.get("SCOPE"))) {
                out.put(String.valueOf(r.get("PRODUCT_CLASS")), ((Number) r.get("SIMM")).doubleValue());
            }
        }
        return out;
    }

    private static Map<String, Object> one(Map<String, Object> answer, String table, String scope) {
        for (Map<String, Object> r : rows(answer, table)) {
            if (scope.equals(r.get("SCOPE"))) {
                return r;
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> miss = (List<Object>) answer.getOrDefault("missing", List.of());
        throw new DrishtiException(ErrorCode.SIMM_NOT_INSTALLED, "the engine " + ENGINE + " answered no " + table + " of " + scope
                + (miss.isEmpty() ? "" : " (not found: " + miss + ")"));
    }

    private static List<Map<String, Object>> rows(Map<String, Object> answer, String name) {
        List<Map<String, Object>> r = com.ash.drishti.banking.exposure.ExposureEnginePath.rows(answer, name);
        return r == null ? List.of() : r;
    }

    private Map<String, Object> call(Principal p, Map<String, Object> params, LocalDate date) {
        Engines e = engines.getIfAvailable();
        if (e == null || !e.running(ENGINE)) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " is not running here (drishti-risk-ccr in lib/engines and "
                    + "config/engines/ccr.yaml); SIMM answers nothing on the engine path without it");
        }
        try {
            return e.ask(p, ENGINE, OUTPUTS, params, Map.of("date", date.toString()));
        } catch (Kernel.Refused r) {
            throw new DrishtiException(r.status() == 403 ? ErrorCode.FORBIDDEN : r.status() == 422 ? ErrorCode.SIMM_NOT_INSTALLED : ErrorCode.ENGINE_BLOCKED,
                    r.getMessage());
        }
    }
}
