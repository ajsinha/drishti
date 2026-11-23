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

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.MeasureSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * What the VaR engine gives a role Home (docs/architecture/ROLE_HOME_SUTRAS.md 2 and 4): the measure codes of the Homes' measures files
 * answered from the engine's own views, as the person (the views apply the person's scope and masks, so a subject they may not see is
 * not in the answer).
 *
 * <table>
 * <caption>Measure codes</caption>
 * <tr><td>VAR, ES</td><td>the headline's figures ({@code var}, {@code es}) of a scope; by subject: the hierarchy's nodes of that level</td></tr>
 * <tr><td>LIM_USE</td><td>the headline's {@code limitUse}; by subject: the nodes' {@code limitUse}</td></tr>
 * <tr><td>PNL_DAY</td><td>the newest day of the scope's history ({@code rows[].pnl})</td></tr>
 * <tr><td>BREACHES</td><td>the days flagged {@code exception} in the scope's history</td></tr>
 * </table>
 * <p>TODO(R2): {@code PNL_MTD}, {@code DV01}, {@code POSITION}, {@code STRESS} have no view in the VaR service; they answer nothing (the
 * Home shows the panel's empty words) until the engines are hosted behind engine-api, then these calls become the hosted engines' outputs.
 * {@link #drivers} is likewise TODO(R2): it needs the engine's what-if overlays by input group.
 */
@Component
final class VarHomeMeasures implements MeasureSource {

    private final VarService svc;
    private final VarViews views;
    private final VarEnginePath engine;

    VarHomeMeasures(VarService svc, VarViews views, VarEnginePath engine) {
        this.svc = svc;
        this.views = views;
        this.engine = engine;
    }

    @Override
    public String engine() {
        return "var";
    }

    @Override
    public boolean on() {
        return engine.consumers() ? engine.running() : svc.running();     // R2: the family switch var.consumers.path
    }

    /** The firm's scope word; a KPI's {@code scope} arg names another ({@code own-desk} and {@code own-books} are the person's rows: the firm scope as they see it). */
    private static String scope(Map<String, Object> args) {
        Object s = args.get("scope");
        return s == null || "firm".equals(s) || String.valueOf(s).startsWith("own-") ? "BANK" : String.valueOf(s);
    }

    @Override
    public OptionalDouble kpi(Principal p, String measure, Map<String, Object> args, LocalDate date) {
        try {
            String scope = scope(args);
            if (engine.consumers()) {
                return engineKpi(p, measure, scope, date);
            }
            return switch (measure) {
                case "VAR" -> number(views.headline(p, scope, date).get("var"));
                case "ES" -> number(views.headline(p, scope, date).get("es"));
                case "LIM_USE" -> number(views.headline(p, scope, date).get("limitUse"));
                case "PNL_DAY" -> lastPnl(views.history(p, scope, date));
                case "BREACHES" -> breaches(views.history(p, scope, date));
                default -> OptionalDouble.empty();                  // TODO(R2): PNL_MTD, DV01
            };
        } catch (RuntimeException e) {
            fault(e);
            return OptionalDouble.empty();                          // no data for the date, or the person may see none: said by the Home
        }
    }

    /** The engine path: the firm (or a node by {@code NODE:id}) from node-var, the days from backtest-days; nothing for another scope word. */
    private OptionalDouble engineKpi(Principal p, String measure, String scope, LocalDate date) {
        String node = scope.regionMatches(true, 0, "NODE:", 0, 5) ? scope.substring(5) : scope;
        if (node.contains(":") || node.contains(" ")) {
            return OptionalDouble.empty();                          // a scope expression: the engine path answers nodes only (R2 step 4b)
        }
        return switch (measure) {
            case "VAR", "ES", "LIM_USE" -> {
                Map<String, Object> n = engine.node(p, date, node);
                yield n == null ? OptionalDouble.empty() : number(n.get(measure.equals("VAR") ? "var" : measure.equals("ES") ? "es" : "limitUse"));
            }
            case "PNL_DAY" -> {
                List<Map<String, Object>> r = engine.history(p, date, node);
                yield r.isEmpty() ? OptionalDouble.empty() : number(r.get(r.size() - 1).get("pnl"));
            }
            case "BREACHES" -> {
                List<Map<String, Object>> r = engine.history(p, date, node);
                yield r.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(r.stream().filter(x -> Boolean.TRUE.equals(x.get("exception"))).count());
            }
            default -> OptionalDouble.empty();
        };
    }

    @Override
    public List<HomeData.MeasureValue> values(Principal p, String measure, String subjectKind, LocalDate date) {
        List<HomeData.MeasureValue> out = new ArrayList<>();
        String key = switch (measure) {
            case "VAR" -> "var";
            case "ES" -> "es";
            case "LIM_USE" -> "limitUse";
            default -> null;                                        // TODO(R2): PNL_DAY by book, POSITION by instrument
        };
        if (key == null) {
            return out;
        }
        for (Map<String, Object> n : nodes(p, date)) {
            if (subjectKind.equalsIgnoreCase(String.valueOf(n.get("level"))) && n.get(key) instanceof Number v) {
                out.add(new HomeData.MeasureValue(subjectKind, String.valueOf(n.get("id")), measure, v.doubleValue()));
            }
        }
        return out;
    }

    @Override
    public List<HomeData.LimitRow> limits(Principal p, String measure, String subjectKind, LocalDate date) {
        List<HomeData.LimitRow> out = new ArrayList<>();
        if (!"VAR".equals(measure)) {
            return out;
        }
        for (Map<String, Object> n : nodes(p, date)) {
            if (subjectKind.equalsIgnoreCase(String.valueOf(n.get("level"))) && n.get("var") instanceof Number v) {
                out.add(new HomeData.LimitRow(String.valueOf(n.get("id")), v.doubleValue(), n.get("limit") instanceof Number l ? l.doubleValue() : null));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Principal p, LocalDate date) {
        try {
            if (engine.consumers()) {
                return engine.nodes(p, date);
            }
            Object rows = views.tree(p, null, date).get("nodes");
            return rows instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (RuntimeException e) {
            fault(e);
            return List.of();
        }
    }

    private void fault(RuntimeException e) {
        if (engine.consumers()) {                                   // the engine path says why a Home panel is empty
            org.slf4j.LoggerFactory.getLogger(VarHomeMeasures.class).warn("role Home measure from the market-risk engine: {}", e.toString());
        }
    }

    private static OptionalDouble number(Object o) {
        return o instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> history) {
        return history.get("rows") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static OptionalDouble lastPnl(Map<String, Object> history) {
        List<Map<String, Object>> r = rows(history);
        return r.isEmpty() ? OptionalDouble.empty() : number(r.get(r.size() - 1).get("pnl"));
    }

    private static OptionalDouble breaches(Map<String, Object> history) {
        List<Map<String, Object>> r = rows(history);
        return r.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(r.stream().filter(x -> Boolean.TRUE.equals(x.get("exception"))).count());
    }
}
