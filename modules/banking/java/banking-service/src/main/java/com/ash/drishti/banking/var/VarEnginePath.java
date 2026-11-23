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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Engines;
import com.ash.drishti.kernel.api.services.Kernel;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * R2 (docs/plans/R2_CUT_PLAN.md, "Switching a route"): the VaR routes answered by Drishti Risk's market-risk engine through the kernel's
 * {@link Engines} service, never by a Risk type. One switch per route family, {@code drishti.module.banking.var.path}: {@code in-tree}
 * (the default until the family's side by side is green) or {@code engine}; a request may name its path ({@code path=engine} or
 * {@code path=in-tree}) so both answers can be compared on one server. The engine's figures are unrounded; the route rounds them to whole
 * units as the in-tree route does.
 */
@Component
public class VarEnginePath {

    /** The engine's id (config/engines/market-risk.yaml). */
    public static final String ENGINE = "market-risk";
    static final String SETTING = "drishti.module.banking.var.path";   // engines/var.yaml

    private final ObjectProvider<Engines> engines;
    private final Environment env;

    VarEnginePath(ObjectProvider<Engines> engines, Environment env) {
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

    static final String CONSUMERS_SETTING = "drishti.module.banking.var.consumers.path";   // engines/var.yaml

    /** True when what reads VaR figures inside Drishti (the role Homes) asks the engine: the consumers' family switch. */
    public boolean consumers() {
        String p = env.getProperty(CONSUMERS_SETTING, "in-tree");
        return switch (p.trim().toLowerCase(Locale.ROOT)) {
            case "engine" -> true;
            case "in-tree" -> false;
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, CONSUMERS_SETTING + " is engine or in-tree, not " + p);
        };
    }

    /** Whether the engine runs here (what a consumer's {@code on()} says on the engine path). */
    public boolean running() {
        Engines e = engines.getIfAvailable();
        return e != null && e.running(ENGINE);
    }

    /**
     * The nodes of the first hierarchy ({@code MGMT} when present) as the tree view's rows are shaped for a consumer: {@code id},
     * {@code level}, {@code var}, {@code es} (whole units), {@code limit} (whole units or null), {@code limitUse}. The window is the
     * configured {@code var.window}, the currency USD, as the in-tree tree. Read as the person: a node they may not see is not a row.
     */
    public List<Map<String, Object>> nodes(Principal p, LocalDate date) {
        Map<String, Object> a = ask(p, List.of("node-var"), windowParams(), Map.of("date", date.toString()));
        List<Map<String, Object>> all = rows(a, "node-var");
        String h = all.stream().anyMatch(r -> "MGMT".equals(r.get("HIERARCHY"))) ? "MGMT"
                : all.isEmpty() ? "" : String.valueOf(all.get(0).get("HIERARCHY"));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : all) {
            if (!h.equals(String.valueOf(r.get("HIERARCHY")))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("NODE_ID"));
            m.put("level", r.get("LEVEL"));
            m.put("var", VarViews.round(((Number) r.get("VAR")).doubleValue()));
            m.put("es", VarViews.round(((Number) r.get("ES")).doubleValue()));
            m.put("limit", r.get("LIMIT") == null ? null : VarViews.round(((Number) r.get("LIMIT")).doubleValue()));
            m.put("limitUse", r.get("LIMIT_USE"));
            out.add(m);
        }
        return out;
    }

    /** One node's row of {@link #nodes}: the firm ({@code BANK}) or a node by its id; null when the person sees none. */
    public Map<String, Object> node(Principal p, LocalDate date, String id) {
        return nodes(p, date).stream().filter(n -> id.equals(String.valueOf(n.get("id")))).findFirst().orElse(null);
    }

    /**
     * A node's tested days at the configured confidence (backtest-days), oldest first, shaped for a consumer: {@code date}, {@code var}
     * (the official VaR the day is held to), {@code pnl} (the hypothetical P&amp;L), {@code exception}.
     */
    public List<Map<String, Object>> history(Principal p, LocalDate date, String node) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", node);
        Map<String, Object> a = ask(p, List.of("backtest-days"), params, Map.of("date", date.toString()));
        double conf = Double.parseDouble(env.getProperty("drishti.module.banking.var.confidence", "0.99"));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> rm : rows(a, "backtest-days")) {
            if (Math.abs(((Number) rm.get("CONFIDENCE")).doubleValue() - conf) > 1e-9) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", String.valueOf(rm.get("DATE")));
            m.put("var", VarViews.round(((Number) rm.get("VAR")).doubleValue()));
            m.put("pnl", VarViews.round(((Number) rm.get("HYPOTHETICAL")).doubleValue()));
            m.put("exception", Boolean.TRUE.equals(rm.get("EXCEPTION")));
            out.add(m);
        }
        out.sort(java.util.Comparator.comparing(m -> String.valueOf(m.get("date"))));
        return out;
    }

    private Map<String, Object> windowParams() {
        String w = env.getProperty("drishti.module.banking.var.window", "250").trim();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("window", w.equalsIgnoreCase("all") ? 0 : Integer.parseInt(w));
        params.put("currency", "USD");
        return params;
    }

    /**
     * A node's headline through the engine: {@code scope}, {@code asOf}, {@code currency}, {@code window}, {@code var}, {@code es} (whole
     * units), {@code varExact}, {@code esExact} (the engine's doubles), {@code trades} (the positions beneath the node), {@code limit},
     * {@code limitUse}, {@code path: engine}. A scope that is not one node is refused in words until the computation contract's row sets
     * reach this route (R2 step 4b).
     */
    public Map<String, Object> headline(Principal p, String scope, LocalDate date, String ccy, String window) {
        String node = node(scope);
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        String currency = ccy == null || ccy.isBlank() ? "USD" : ccy.trim().toUpperCase(Locale.ROOT);
        int w = window == null || window.isBlank() || window.equalsIgnoreCase("all") ? 0 : Integer.parseInt(window.trim());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("window", w);
        params.put("currency", currency);
        Map<String, Object> a = ask(p, List.of("node-var"), params, Map.of("date", date.toString()));
        Map<String, Object> row = row(a, "node-var", "NODE_ID", node);
        if (row == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no node " + node + " on " + date
                    + missing(a));
        }
        double var = ((Number) row.get("VAR")).doubleValue();
        double es = ((Number) row.get("ES")).doubleValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("asOf", date.toString());
        out.put("currency", currency);
        out.put("window", w == 0 ? "all" : Integer.toString(w));
        out.put("var", VarViews.round(var));
        out.put("es", VarViews.round(es));
        out.put("varExact", var);
        out.put("esExact", es);
        out.put("trades", ((Number) row.get("MEMBERS")).longValue());
        out.put("limit", row.get("LIMIT") == null ? null : VarViews.round(((Number) row.get("LIMIT")).doubleValue()));
        out.put("limitUse", row.get("LIMIT_USE"));
        out.put("path", "engine");
        return out;
    }

    /** {@code NODE:<id>} (the node's id as the tree names it, {@code MGMT~DESK-RATES}) or {@code BANK} (the first hierarchy's root). */
    static String node(String scope) {
        String s = scope == null ? "" : scope.trim();
        if (s.regionMatches(true, 0, "NODE:", 0, 5) && s.indexOf(' ') < 0) {
            return s.substring(5);
        }
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers one node (NODE:<id>) for now; " + s
                + " is answered in-tree (path=in-tree) until row-set scopes reach the engine path (R2 step 4b)");
    }

    private Map<String, Object> ask(Principal p, List<String> outputs, Map<String, Object> params, Map<String, String> snapshot) {
        Engines e = engines.getIfAvailable();
        if (e == null || !e.running(ENGINE)) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " is not running here (drishti-risk-market in "
                    + "lib/engines and config/engines/market-risk.yaml); the VaR routes answer nothing on the engine path without it");
        }
        try {
            return e.ask(p, ENGINE, outputs, params, snapshot);
        } catch (Kernel.Refused r) {
            throw new DrishtiException(r.status() == 403 ? ErrorCode.FORBIDDEN : ErrorCode.ENGINE_BLOCKED, r.getMessage());
        }
    }

    /** A table's rows by column name (the answer's rows are positional, in the columns' order). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> answer, String table) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object t : (List<Object>) answer.getOrDefault("tables", List.of())) {
            Map<String, Object> tm = (Map<String, Object>) t;
            if (table.equals(tm.get("name"))) {
                List<String> names = new java.util.ArrayList<>();
                for (Object c : (List<Object>) tm.getOrDefault("columns", List.of())) {
                    names.add(String.valueOf(((Map<String, Object>) c).get("name")));
                }
                for (Object r : (List<Object>) tm.getOrDefault("rows", List.of())) {
                    Object[] cells = r instanceof Object[] a ? a : ((List<Object>) r).toArray();
                    Map<String, Object> rm = new LinkedHashMap<>();
                    for (int i = 0; i < names.size() && i < cells.length; i++) {
                        rm.put(names.get(i), cells[i]);
                    }
                    out.add(rm);
                }
            }
        }
        return out;
    }

    static Map<String, Object> row(Map<String, Object> answer, String table, String keyColumn, String key) {
        for (Map<String, Object> r : rows(answer, table)) {
            if (key.equals(String.valueOf(r.get(keyColumn)))) {
                return r;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String missing(Map<String, Object> a) {
        List<Object> m = (List<Object>) a.getOrDefault("missing", List.of());
        return m.isEmpty() ? "" : " (not found: " + m + ")";
    }
}
