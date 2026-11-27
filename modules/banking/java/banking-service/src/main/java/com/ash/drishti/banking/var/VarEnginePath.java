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
import com.ash.drishti.banking.compute.var.ScopeExpr;
import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.kernel.api.services.Engines;
import com.ash.drishti.kernel.api.services.Kernel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * R2 (docs/plans/R2_CUT_PLAN.md, "Switching a route"): the VaR routes answered by Drishti Risk's market-risk engine through the kernel's
 * {@link Engines} service, never by a Risk type. One switch per route family, {@code drishti.module.banking.var.route}: {@code in-tree}
 * (the default until the family's side by side is green) or {@code engine}; a request may name its path ({@code path=engine} or
 * {@code path=in-tree}) so both answers can be compared on one server. The engine's figures are unrounded; the route rounds them to whole
 * units as the in-tree route does.
 */
@Component
public class VarEnginePath {

    /** The engine's id (config/engines/market-risk.yaml). */
    public static final String ENGINE = "market-risk";
    static final String SETTING = "drishti.module.banking.var.route";   // engines/var.yaml

    private final ObjectProvider<Engines> engines;
    private final Environment env;
    /** The saved and system baskets and captures (Drishti's, kept); the in-tree engine half is not asked for a figure. */
    private final ObjectProvider<VarService> service;

    /** The limit status words (within, near, breach) of a use: the banking module's configured rule. */
    private final ObjectProvider<com.ash.drishti.banking.RiskLimits> limits;

    VarEnginePath(ObjectProvider<Engines> engines, Environment env, ObjectProvider<VarService> service,
            ObjectProvider<com.ash.drishti.banking.RiskLimits> limits) {
        this.engines = engines;
        this.env = env;
        this.service = service;
        this.limits = limits;
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

    static final String CONSUMERS_SETTING = "drishti.module.banking.var.consumers.route";   // engines/var.yaml

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

    private Map<String, Object> windowParams() {
        String w = env.getProperty("drishti.module.banking.var.window", "250").trim();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("window", w.equalsIgnoreCase("all") ? 0 : Integer.parseInt(w));
        params.put("currency", "USD");
        return params;
    }

    /**
     * A scope's headline through the engine (R2_CUT_PLAN 4c; ENGINE_INTEGRATION_DESIGN 18.1): {@code scope}, {@code asOf}, {@code currency},
     * {@code window}, {@code var}, {@code es} (whole units), {@code varExact}, {@code esExact} (the engine's doubles), {@code trades} (the
     * rows of the scope the caller sees), {@code limit}, {@code limitUse}, {@code path: engine}. Every scope kind (a node, a system or saved
     * basket, a capture, a scope expression, an ad-hoc list) is resolved here, by Drishti's resolver over the engine's index of the date,
     * to a row set; the engine sums the rows and never learns the kind. A node whole is the engine's node figure.
     *
     * <p>The window is the in-tree route's: the date's VaR is on the configured window ({@code drishti.module.banking.var.window}); a
     * {@code window} asked recomputes VaR and ES on the newest N of those scenarios (more than the date's: all of them, with a warning),
     * and the rest of the answer, limit use included, stays the date's (VAR_GUIDE.md section 18.5: a limit is set against the configured
     * VaR, never against a figure asked for comparison).
     */
    public Map<String, Object> headline(Principal p, String scope, LocalDate date, String ccy, String window) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        String currency = VarCurrencies.normalise(ccy);   // none named: the installation's reporting currency
        int dateWindow = dateWindow();
        int asked = window == null || window.isBlank() || window.equalsIgnoreCase("all") ? 0 : Integer.parseInt(window.trim());
        int w = dateWindow == 0 ? asked : asked == 0 || asked > dateWindow ? dateWindow : asked;
        String warning = dateWindow > 0 && asked > dateWindow ? "window " + asked + " is more than the " + dateWindow
                + " scenarios of the date: all of them used" : null;
        String s = scope == null ? "" : scope.trim();
        Figures f;
        Figures official;
        String resolvedNode = null;
        if (s.regionMatches(true, 0, "NODE:", 0, 5) && s.indexOf(' ') < 0) {
            String node = s.substring(5);
            resolvedNode = node;
            f = nodeFigures(p, node, date, currency, w);
            official = w == dateWindow ? f : nodeFigures(p, node, date, currency, dateWindow);
        } else {
            EngineScopeBook book = index(p, date, currency);
            ScopeResolver.Resolution res = resolve(p, book, s, date);
            if (res.node() != null) {
                resolvedNode = res.node();
                f = nodeFigures(p, res.node(), date, currency, w);
                official = w == dateWindow ? f : nodeFigures(p, res.node(), date, currency, dateWindow);
            } else {
                double limit = res.canonical().startsWith("PORT:")              // the index's limits are in the answer's currency
                        ? book.basketLimits.getOrDefault(res.canonical().substring(5), Double.NaN) : Double.NaN;
                f = rowFigures(p, res, date, currency, w, limit);
                official = w == dateWindow ? f : rowFigures(p, res, date, currency, dateWindow, limit);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("asOf", date.toString());
        out.put("currency", currency);
        out.put("window", asked == 0 ? "all" : Integer.toString(asked));
        out.put("node", resolvedNode);                                  // the node the scope is whole (its figures are the node's)
        out.put("var", VarViews.round(f.var()));
        out.put("es", VarViews.round(f.es()));
        out.put("varExact", f.var());
        out.put("esExact", f.es());
        out.put("trades", f.members());
        out.put("limit", Double.isNaN(official.limit()) ? null : VarViews.round(official.limit()));
        out.put("limitUse", Double.isNaN(official.limit()) || official.limit() == 0 ? null : official.var() / official.limit());
        if (resolvedNode != null) {                                     // the parts (child nodes): standalone sum and diversification
            EngineScopeBook book = index(p, date, currency);
            VarService svc = service.getIfAvailable();
            boolean leaf = book.children.getOrDefault(resolvedNode, List.of()).isEmpty();
            if (!leaf || svc != null) {                                 // a leaf node's parts are its positions, every one (never cut)
                ScopeResolver.Resolution nr = resolve(p, book, "NODE:" + resolvedNode, date);
                Parts parts = leaf ? positionParts(p, svc, book, nr, date, currency, false) : nodeParts(p, book, nr, date, currency);
                double alone = 0;
                for (Map<String, Object> r : parts.rows()) {
                    alone += (Double) r.get("_alone");
                }
                int n = parts.rows().size();
                out.put("children", n);
                out.put("childKind", n == 0 ? null : parts.rows().get(0).get("kind"));
                out.put("sumStandalone", n == 0 ? null : VarViews.round(alone));
                out.put("diversification", n < 2 ? null : VarViews.round(alone - parts.var()));
            }
        }
        if (warning != null) {
            out.put("warning", warning);
        }
        out.put("path", "engine");
        return out;
    }

    /**
     * A node's headline through a lens other than the offered one ({@code horizon} 10d by the square root of 10 or overlapping sums,
     * {@code set=stressed}), from the engine's {@code node-var-horizon}: {@code var}, {@code es} (and their exact doubles), {@code trades},
     * {@code limit}, and {@code limitUse} only when the limit is set for that lens (a 1-day limit is not compared with a 10-day VaR, a
     * stressed VaR never with a limit), as the in-tree headline. A scope that is not a node whole is answered in-tree for now.
     */
    public Map<String, Object> headline(Principal p, String scope, LocalDate date, String ccy, com.ash.drishti.banking.compute.var.VarLens lens) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        VarService svc = service.getIfAvailable();
        String currency = VarCurrencies.normalise(ccy);
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, scope == null ? "" : scope.trim(), date);
        if (res.node() == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers a horizon or the stress set for a node for now; "
                    + res.canonical() + " is not a node: ask it in-tree (path=in-tree)");
        }
        Map<String, Object> params = params(dateWindow(), currency);
        params.put("stressed", lens.stressed());
        params.put("horizon", lens.tenDay() ? "10d" : "1d");
        params.put("ten-day-method", lens.method() == com.ash.drishti.banking.compute.var.VarLens.Method.SUM ? "sum" : "sqrt10");
        lensConfidences(params, lens);
        Map<String, Object> a = ask(p, List.of("node-var-horizon"), params, Map.of("date", date.toString()));
        Map<String, Object> r = row(a, "node-var-horizon", "NODE_ID", res.node());
        if (r == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no " + lens.label() + " VaR for "
                    + res.canonical() + " on " + date + missing(a));
        }
        double var = num(r.get("VAR"));
        double es = num(r.get("ES"));
        double limit = nodeFigures(p, res.node(), date, currency, dateWindow()).limit();   // the node's limit in the answer's currency
        boolean comparable = svc != null && svc.loader().limitBasis(date, res.node()).matches(lens);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("asOf", date.toString());
        out.put("currency", currency);
        out.put("node", res.node());
        out.put("var", VarViews.round(var));
        out.put("es", VarViews.round(es));
        out.put("varExact", var);
        out.put("esExact", es);
        out.put("scenarios", ((Number) r.get("SCENARIOS")).intValue());
        out.put("confidence", lens.confidence());
        out.put("esConfidence", lens.esConfidence());
        out.put("horizon", lens.horizon());
        out.put("method", lens.methodName());
        out.put("set", lens.set());
        out.put("trades", res.set().size());
        out.put("limit", Double.isNaN(limit) ? null : VarViews.round(limit));
        out.put("limitComparable", Double.isNaN(limit) ? null : comparable);
        out.put("limitUse", Double.isNaN(limit) || limit == 0 || !comparable ? null : var / limit);
        out.put("path", "engine");
        return out;
    }

    /**
     * A scope's P&amp;L path through the engine ({@code GET /scope/path}, VAR_GUIDE.md section 15): the engine's {@code scope-path} (the
     * summed scenario P&amp;L ranked in the measures' own order, the VaR rank, the ES count, the histogram) and {@code scope-scenarios} (the
     * window's dates), shaped as the in-tree route shapes them: {@code canonical}, {@code currency}, {@code var}, {@code es}, {@code k},
     * {@code esCount}, {@code lens}, {@code histogram}, {@code worst} (25), {@code series}, {@code dates}, {@code scenarioDates}, and the
     * ranked table a page at a time with the part that lost most on each scenario ({@code from}, {@code fromScope}, {@code fromPnl}).
     * The parts are a node's child nodes (each the engine's figure of the child whole) or a leaf node's positions (the host's labels, each
     * position's rows summed by the engine's {@code scope-var}); a trade has none. Other scope kinds (an expression or a list, whose parts
     * are terms or positions) are answered in-tree for now, said in one sentence.
     */
    public Map<String, Object> path(Principal p, String scope, LocalDate date, int page, int size, String ccy) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the path is answered nowhere");
        }
        String currency = VarCurrencies.normalise(ccy);   // none named: the installation's reporting currency
        int window = dateWindow();
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, scope, date);
        List<String> kids = res.node() == null ? List.of() : book.children.getOrDefault(res.node(), List.of());
        if (res.node() == null && res.trade() < 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers the P&L path of a node or of one trade for now; the "
                    + "parts of " + res.canonical() + " are positions or terms: ask it in-tree (path=in-tree)");
        }
        HostPositions positions = res.node() != null && kids.isEmpty() ? hostPositions(p, svc, book, res, date, currency, true) : null;
        Map<String, Object> params = params(window, currency);
        params.put("rows", ordinals(res.set()));
        params.put("node", res.node() == null ? "" : res.node());
        params.put("bins", svc.props().histogramBins());
        Map<String, Object> a = ask(p, List.of("scope-path"), params, Map.of("date", date.toString()));
        List<Map<String, Object>> r = rows(a, "scope-path");
        if (r.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no path for " + res.canonical() + " on "
                    + date + missing(a));
        }
        Map<String, Object> x = r.get(0);
        double[] v = doubles(x.get("PNL"));
        int[] order = ints(x.get("ORDER"));
        List<String> dates = new ArrayList<>();
        for (Map<String, Object> d : rows(ask(p, List.of("scope-scenarios"), Map.of("window", window), Map.of("date", date.toString())),
                "scope-scenarios")) {
            dates.add(String.valueOf(d.get("DATE")));
        }
        if (dates.size() != v.length) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " answered " + v.length + " scenario P&Ls over "
                    + dates.size() + " scenario dates for " + res.canonical());
        }
        // the parts: each child node whole, the engine's figure of it (the in-tree route takes the child's stored aggregate)
        List<Object[]> parts = new ArrayList<>();
        com.ash.drishti.banking.compute.var.Cells.Names names = svc.loader().names(com.ash.drishti.api.AsOf.of(date), svc.hierarchies());
        for (String c : kids) {
            OrdinalSet m = book.members(c).and(res.set());
            if (m.isEmpty()) {
                continue;
            }
            Map<String, Object> cp = params(window, currency);
            cp.put("rows", ordinals(m));
            cp.put("node", c);
            List<Map<String, Object>> cr = rows(ask(p, List.of("scope-var"), cp, Map.of("date", date.toString())), "scope-var");
            if (!cr.isEmpty()) {
                String value = c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('~')) + 1);
                String named = book.levels.get(c) == null ? null : names.of(book.levels.get(c), value);   // the reference entity's name (VAR_GUIDE 15.14)
                parts.add(new Object[] {named != null ? named : book.names.getOrDefault(c, c), "NODE:" + c, doubles(cr.get(0).get("PNL")), null});
            }
        }
        for (int i = 0; positions != null && i < positions.parts().size(); i++) {   // a leaf node's positions, the host's labels (lane G part 6)
            VarViews.Part pt = positions.parts().get(i);
            Map<String, Object> cp = params(window, currency);
            cp.put("rows", positions.rows().get(i));
            cp.put("node", "");
            List<Map<String, Object>> cr = rows(ask(p, List.of("scope-var"), cp, Map.of("date", date.toString())), "scope-var");
            if (cr.isEmpty()) {
                throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " answered no P&L for " + pt.scope() + " on " + date);
            }
            parts.add(new Object[] {pt.label(), pt.scope(), doubles(cr.get(0).get("PNL")), pt.attrs().get("tradeCurrency")});
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", res.canonical());
        out.put("currency", currency);
        out.put("intraday", null);
        out.put("var", VarViews.round(((Number) x.get("VAR")).doubleValue()));
        out.put("es", VarViews.round(((Number) x.get("ES")).doubleValue()));
        out.put("varExact", ((Number) x.get("VAR")).doubleValue());
        out.put("esExact", ((Number) x.get("ES")).doubleValue());
        out.put("k", ((Number) x.get("K")).intValue());
        out.put("esCount", ((Number) x.get("ES_COUNT")).intValue());
        Map<String, Object> lens = new LinkedHashMap<>();
        lens.put("horizon", "1d");
        lens.put("default", true);
        lens.put("scenarios", v.length);
        lens.put("set", "NORMAL");
        out.put("lens", lens);
        double[] edges = doubles(x.get("EDGES"));
        long[] e = new long[edges.length];
        for (int i = 0; i < e.length; i++) {
            e[i] = VarViews.round(edges[i]);
        }
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("edges", e);
        hist.put("counts", ints(x.get("COUNTS")));
        out.put("histogram", hist);
        out.put("edgesExact", edges);                               // the host's parametric curve reads the raw edges (removed before answering)
        List<Map<String, Object>> worst = new ArrayList<>();
        for (int k = 0; k < Math.min(25, order.length); k++) {
            worst.add(scenarioRow(dates, order[k], k + 1, v[order[k]]));
        }
        out.put("worst", worst);
        long[] series = new long[v.length];
        for (int j = 0; j < v.length; j++) {
            series[j] = Math.round(v[j]);
        }
        out.put("series", series);
        out.put("dates", List.of(dates.get(0), dates.get(dates.size() - 1)));
        out.put("scenarioDates", dates);
        int sz = size <= 0 ? svc.props().pathPage() : Math.min(size, Math.max(509, order.length));
        int from = Math.max(0, page) * sz;
        List<Map<String, Object>> table = new ArrayList<>();
        for (int k = from; k < Math.min(order.length, from + sz); k++) {
            int j = order[k];
            Map<String, Object> row = scenarioRow(dates, j, k + 1, v[j]);
            Object[] worstPart = null;
            for (Object[] pt : parts) {
                if (worstPart == null || ((double[]) pt[2])[j] < ((double[]) worstPart[2])[j]) {
                    worstPart = pt;
                }
            }
            if (worstPart != null) {
                if (worstPart[3] != null) {
                    row.put("fromCurrency", worstPart[3]);
                }
                row.put("from", worstPart[0]);
                row.put("fromScope", worstPart[1]);
                row.put("fromPnl", VarViews.round(((double[]) worstPart[2])[j]));
            }
            table.add(row);
        }
        out.put("page", Math.max(0, page));
        out.put("pageSize", sz);
        out.put("total", order.length);
        out.put("rows", table);
        out.put("path", "engine");
        return out;
    }

    /**
     * A hierarchy's tree through the engine ({@code GET /tree}, the explorer's left pane): every node of {@code node-var} the person sees,
     * walked from the root in the engine's node order (each node's children in order, depth first, as the in-tree walk), each row the
     * in-tree row's fields ({@code id}, {@code scope}, {@code name}, {@code level}, {@code parent}, {@code depth}, {@code var}, {@code es},
     * {@code limit}, {@code limitUse}, {@code limitStatus}, {@code limitComparable}, {@code trades}, {@code hidden}, {@code expandable},
     * the (Blank) note) plus {@code varExact} and {@code esExact}. The hierarchies' titles and the system baskets are Drishti's. A lens,
     * the matured-items switch, a basket's own tree ({@code within}) and the parametric cells are answered in-tree for now.
     */
    public Map<String, Object> tree(Principal p, String hierarchy, LocalDate date, String ccy) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the tree is answered nowhere");
        }
        String currency = VarCurrencies.normalise(ccy);   // none named: the installation's reporting currency
        EngineScopeBook book = index(p, date, currency);
        List<String> present = book.hierarchies();
        String h = hierarchy == null || hierarchy.isBlank() ? (present.contains("MGMT") || present.isEmpty() ? "MGMT" : present.get(0))
                : hierarchy.toUpperCase(Locale.ROOT);
        String root = book.rootId(h);
        if (root == null) {
            throw new DrishtiException(ErrorCode.VAR_HIERARCHY_UNKNOWN, "no hierarchy " + h + " on " + date + " (it has "
                    + (present.isEmpty() ? "none: configure hierarchies: in the pack or drishti.var.hierarchies" : String.join(", ", present)) + ")");
        }
        Map<String, Object> a = ask(p, List.of("node-var"), params(dateWindow(), currency), Map.of("date", date.toString()));
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> r : rows(a, "node-var")) {
            byId.put(String.valueOf(r.get("NODE_ID")), r);
        }
        com.ash.drishti.banking.compute.var.Cells.Names names = svc.loader().names(com.ash.drishti.api.AsOf.of(date), svc.hierarchies());
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
        stack.push(new Object[] {root, 0});
        while (!stack.isEmpty()) {
            Object[] top = stack.pop();
            String id = (String) top[0];
            int depth = (Integer) top[1];
            Map<String, Object> r = byId.get(id);
            if (r == null) {
                continue;                                                // a node the person does not see: not a row, nor its subtree
            }
            OrdinalSet members = book.members(id);
            List<String> kids = book.children.getOrDefault(id, List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("scope", "NODE:" + id);
            m.put("name", nodeName(book, names, id));
            m.put("level", r.get("LEVEL"));
            m.put("parent", r.get("PARENT"));
            m.put("depth", depth);
            double var = ((Number) r.get("VAR")).doubleValue();
            double es = ((Number) r.get("ES")).doubleValue();
            m.put("var", VarViews.round(var));
            m.put("es", VarViews.round(es));
            m.put("varExact", var);
            m.put("esExact", es);
            double limit = r.get("LIMIT") == null ? Double.NaN : ((Number) r.get("LIMIT")).doubleValue();
            boolean noLimit = Double.isNaN(limit);
            boolean comparable = svc.loader().limitBasis(date, id).matches(svc.props().defaultLens());
            m.put("limit", noLimit ? null : VarViews.round(limit));
            m.put("limitUse", noLimit || limit == 0 || !comparable ? null : var / limit);
            com.ash.drishti.banking.RiskLimits rl = limits.getIfAvailable();
            m.put("limitStatus", rl == null ? null : rl.status((Double) m.get("limitUse")));
            m.put("limitComparable", noLimit ? null : comparable);
            int trades = members == null ? 0 : members.size();
            m.put("trades", trades);
            m.put("hidden", 0);
            m.put("expandable", !kids.isEmpty() || trades > 0);
            String blank = svc.hierarchies().blankNote(h, id, String.valueOf(r.get("LEVEL")), trades);
            if (blank != null) {
                m.put("blank", true);
                m.put("blankNote", blank);
            }
            out.add(m);
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(new Object[] {kids.get(i), depth + 1});
            }
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("hierarchy", h);
        t.put("currency", currency);
        t.put("intraday", null);
        List<Map<String, Object>> info = svc.hierarchies().describe(present);
        t.put("hierarchies", info.stream().map(x -> String.valueOf(x.get("id"))).toList());
        t.put("hierarchyInfo", info);
        t.put("title", svc.hierarchies().title(h));
        t.put("businessDate", date.toString());
        t.put("nodes", out);
        List<Map<String, Object>> ports = new ArrayList<>();
        svc.saved().system().portfolios(date, book.basketLimits).values().forEach(pf -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", pf.id());
            x.put("scope", "PORT:" + pf.id());
            x.put("name", pf.name());
            x.put("type", pf.type());
            x.put("owner", pf.owner());
            svc.saved().system().get(pf.id()).ifPresent(sb -> x.put("description", sb.description()));
            ports.add(x);
        });
        t.put("portfolios", ports);
        t.put("path", "engine");
        return t;
    }

    /**
     * A node's parts through the engine ({@code GET /scope/contributions}, {@code by} child or node): each child node the scope holds, measured
     * by the engine's {@code scope-contributions} in the scope (standalone VaR and ES, component VaR and ES, share, incremental VaR), shaped
     * as the in-tree route ({@code canonical}, {@code currency}, {@code by}, {@code var}, {@code es}, {@code sumStandalone},
     * {@code sumComponent}, {@code diversification}, {@code truncated}, {@code total}, {@code shown}, the rows largest component first).
     * A leaf node's parts are its positions ({@link #hostPositions}: the host's labels, sizes and attributes, the engine's figures). A
     * scope that is not a node, other parts (trades, terms, desks, books, attribute groups) and a scenario's column are answered in-tree
     * for now, said in one sentence.
     */
    public Map<String, Object> contributions(Principal p, String scope, LocalDate date, String ccy) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        String currency = VarCurrencies.normalise(ccy);   // none named: the installation's reporting currency
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, scope, date);
        Parts parts = nodeParts(p, book, res, date, currency);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", res.canonical());
        out.put("currency", currency);
        out.put("intraday", null);
        out.put("by", parts.rows().isEmpty() ? null : parts.rows().get(0).get("kind"));   // the in-tree route names the parts' level
        out.put("var", VarViews.round(parts.var()));
        out.put("es", VarViews.round(parts.es()));
        out.put("varExact", parts.var());
        out.put("esExact", parts.es());
        double standalone = 0;
        double comp = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : parts.rows()) {
            standalone += (Double) r.remove("_alone");
            comp += (Double) r.remove("_comp");
            rows.add(r);
        }
        rows.sort(java.util.Comparator.comparingDouble(r -> -((Number) r.get("componentVar")).doubleValue()));
        out.put("sumStandalone", VarViews.round(standalone));
        out.put("sumComponent", VarViews.round(comp));
        out.put("diversification", VarViews.round(standalone - parts.var()));
        out.put("truncated", false);
        out.put("total", rows.size());
        out.put("shown", rows.size());
        out.put("rows", rows);
        out.put("path", "engine");
        return out;
    }

    /** A node's name as the in-tree book names it: the reference entity's name of its value (VAR_GUIDE 15.14), else the engine's name. */
    /**
     * A scope's history through the engine ({@code GET /scope/history}, offered lens), the replay a scope without official records gets
     * in-tree: the engine's {@code scope-backtest} (the newest {@code history-days} scenario P&amp;Ls of the scope's rows against its VaR
     * of the date, each day tested against the VaR before it; in-sample) and {@code scope-scenarios} (the days' dates), shaped as the
     * in-tree route shapes them: {@code currency}, {@code lens}, {@code mode: replay}, {@code note}, {@code backtest}, {@code rows}. A node
     * with official records (two days or more) that the caller sees whole is answered from them ({@code mode: official}): the engine's
     * {@code official-history} and {@code official-backtest} over {@code var-history}, the published records (USD); a node with official
     * records in another currency is answered in-tree, said in one sentence.
     */
    public Map<String, Object> history(Principal p, String scope, LocalDate date, String ccy,
            java.util.function.IntFunction<Map<String, Object>> lens) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the history is answered nowhere");
        }
        String currency = VarCurrencies.normalise(ccy);
        int window = dateWindow();
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, scope, date);
        // a node's official records (two days or more) are its history, in-tree from its store, here from the engine's var-history (the
        // published records: USD, the one currency published); the replay otherwise, and when the caller does not see the whole node
        int stored = 0;
        if (res.node() != null) {
            VarOfficialStore.NodeHistory h = svc.official().history(res.node(), date, svc.props().historyDays(), currency);
            stored = h == null ? 0 : h.dates().length;
            if (stored > 1 && !VarDate.USD.equals(currency)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path reads the published official records (USD): " + res.canonical()
                        + " has official records in " + currency + " (" + stored + " days): ask it in-tree (path=in-tree)");
            }
        }
        boolean officialToo = stored > 1;
        Map<String, Object> params = params(window, currency);
        params.put("rows", ordinals(res.set()));
        params.put("node", res.node() == null ? "" : res.node());
        params.put("days", svc.props().historyDays());
        Map<String, Object> a = ask(p, officialToo ? List.of("scope-backtest", "official-history", "official-backtest") : List.of("scope-backtest"),
                params, Map.of("date", date.toString()));
        List<Map<String, Object>> r = rows(a, "scope-backtest");
        if (r.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no history for " + res.canonical() + " on "
                    + date + missing(a));
        }
        Map<String, Object> x = r.get(0);
        double[] pnl = doubles(x.get("PNL"));
        double[] flags = doubles(x.get("FLAGS"));
        int first = ((Number) x.get("FIRST")).intValue();
        List<String> dates = new ArrayList<>();
        for (Map<String, Object> d : rows(ask(p, List.of("scope-scenarios"), Map.of("window", window), Map.of("date", date.toString())),
                "scope-scenarios")) {
            dates.add(String.valueOf(d.get("DATE")));
        }
        if (officialToo && res.set().size() == ((Number) x.get("MEMBERS")).intValue()) {
            List<Map<String, Object>> days = rows(a, "official-history");
            List<Map<String, Object>> b = rows(a, "official-backtest");
            if (days.size() != stored || b.isEmpty()) {
                throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " reads " + days.size() + " official records of "
                        + res.canonical() + " to " + date + ", the official store holds " + stored + " (published: drishti.var.official.publish)"
                        + missing(a) + ": ask it in-tree (path=in-tree)");
            }
            return official(currency, lens.apply(dates.size()), days, b.get(0));
        }
        if (first + pnl.length != dates.size()) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " replayed " + pnl.length + " days from scenario "
                    + first + " over " + dates.size() + " scenario dates for " + res.canonical());
        }
        double var = num(x.get("VAR"));
        double es = num(x.get("ES"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < pnl.length; i++) {
            rows.add(VarViews.historyRow(LocalDate.parse(dates.get(first + i)), var, es, pnl[i], flags[i] != 0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", currency);
        out.put("lens", lens.apply(dates.size()));                 // the lens over the date's scenarios, as in-tree
        out.put("mode", "replay");
        int hidden = res.set().size() - ((Number) x.get("MEMBERS")).intValue();
        out.put("note", "today's members over the last " + pnl.length + " scenario days against today's VaR: in-sample, not a regulatory "
                + "backtest" + (res.node() != null && hidden > 0 ? " (the official history covers trades outside your entitlement)" : ""));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", ((Number) x.get("DAYS")).intValue());
        m.put("exceptions", ((Number) x.get("EXCEPTIONS")).intValue());
        m.put("light", x.get("ZONE"));
        m.put("kupiecLr", num(x.get("KUPIEC_LR")));
        m.put("kupiecPValue", num(x.get("KUPIEC_P")));
        m.put("christoffersenLr", num(x.get("CHRISTOFFERSEN_LR")));
        m.put("christoffersenPValue", num(x.get("CHRISTOFFERSEN_P")));
        out.put("backtest", m);
        out.put("rows", rows);
        out.put("path", "engine");
        return out;
    }

    /** The history of a node with official records, shaped as the in-tree route shapes it ({@code mode: official}). */
    private static Map<String, Object> official(String currency, Map<String, Object> lens, List<Map<String, Object>> days, Map<String, Object> b) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : days) {
            rows.add(VarViews.historyRow(LocalDate.parse(String.valueOf(d.get("DATE")).substring(0, 10)), num(d.get("VAR")), num(d.get("ES")),
                    num(d.get("HYPO_PNL")), Boolean.TRUE.equals(d.get("EXCEPTION"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currency", currency);
        out.put("lens", lens);
        out.put("mode", "official");
        out.put("note", "the node's official end-of-day captures, one a business day (" + rows.size() + " days)");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", ((Number) b.get("DAYS")).intValue());
        m.put("exceptions", ((Number) b.get("EXCEPTIONS")).intValue());
        m.put("light", b.get("ZONE"));
        m.put("kupiecLr", num(b.get("KUPIEC_LR")));
        m.put("kupiecPValue", num(b.get("KUPIEC_P")));
        m.put("christoffersenLr", num(b.get("CHRISTOFFERSEN_LR")));
        m.put("christoffersenPValue", num(b.get("CHRISTOFFERSEN_P")));
        out.put("backtest", m);
        out.put("rows", rows);
        out.put("path", "engine");
        return out;
    }

    /**
     * A node's period statistics through the engine ({@code GET /statistics}, {@code path=engine}): the engine's {@code official-statistics}
     * over {@code var-history} (the published records) on the newest official date, shaped as {@link VarPeriodStats#stats} shapes them.
     * Refused in words when the engine's records are not the store's (publish off, a session opened before a capture, or a synthetic demo
     * history, which is never published) or when its period, average days or stress weeks are configured otherwise than here.
     */
    public Map<String, Object> statistics(Principal p, String node, String measure, VarPeriodStats.Period period, int averageDays, int stressWeeks) {
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the statistics are answered nowhere");
        }
        VarOfficialStore store = svc.official();
        if (store.dates().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no official VaR record yet: the engine path opens the newest official date; ask it "
                    + "in-tree (path=in-tree)");
        }
        LocalDate newest = store.dates().last();
        int stored = 0;
        for (LocalDate d : store.dates().subSet(period.from(), true, period.to(), true)) {
            Map<String, Object> rec = store.index(d).orElse(null);
            if (rec != null && Boolean.TRUE.equals(rec.get("synthetic"))) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the official records of " + period.name() + " hold a synthetic demo history, "
                        + "which is never published: the engine reads the published records; ask it in-tree (path=in-tree)");
            }
            if (rec != null && rec.get("nodes") instanceof Map<?, ?> nodes && nodes.get(node) instanceof Map<?, ?> f
                    && Double.isFinite(VarOfficialStore.num(f.get(measure)))) {
                stored++;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("node", node);
        params.put("period", period.name());
        params.put("measure", measure);
        Map<String, Object> a = ask(p, List.of("official-statistics"), params, Map.of("date", newest.toString()));
        List<Map<String, Object>> r = rows(a, "official-statistics");
        if (r.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no statistics of " + node + missing(a));
        }
        Map<String, Object> x = r.get(0);
        String engineNewest = x.get("NEWEST") == null ? null : String.valueOf(x.get("NEWEST"));
        String engineOldest = x.get("OLDEST") == null ? null : String.valueOf(x.get("OLDEST"));
        int records = ((Number) x.get("RECORDS")).intValue();
        if (!newest.toString().equals(engineNewest) || !store.dates().first().toString().equals(engineOldest) || records != stored) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " reads " + records + " official records of " + node
                    + " in " + period.name() + " (" + engineOldest + " to " + engineNewest + "), the official store " + stored + " ("
                    + store.dates().first() + " to " + newest + "; published: drishti.var.official.publish): ask it in-tree (path=in-tree)");
        }
        if (!period.from().toString().equals(String.valueOf(x.get("FIRST_DAY"))) || !period.to().toString().equals(String.valueOf(x.get("LAST_DAY")))
                || ((Number) x.get("AVERAGE_N_DAYS")).intValue() != averageDays || ((Number) x.get("STRESS_WEEKS")).intValue() != stressWeeks) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " reads " + period.name() + " as " + x.get("FIRST_DAY")
                    + " to " + x.get("LAST_DAY") + " with " + x.get("AVERAGE_N_DAYS") + " days and " + x.get("STRESS_WEEKS") + " weeks, here "
                    + period.from() + " to " + period.to() + " with " + averageDays + " and " + stressWeeks + " (its regulatory options differ): "
                    + "ask it in-tree (path=in-tree)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", node);
        out.put("measure", measure);
        out.put("period", period.name());
        out.put("from", period.from().toString());
        out.put("to", period.to().toString());
        boolean preview = newest.isBefore(period.to());
        out.put("preview", preview);
        if (preview) {
            out.put("previewNote", period.name() + " closes " + period.to() + ": the figures run to the newest official record, " + newest
                    + "; the run of record is made on the period end");
        }
        out.put("records", records);
        out.put("synthetic", 0L);                                       // never published: refused above when the period holds one
        if (records == 0) {
            out.put("note", "no official " + measure + " of " + node + " in " + period.name());
            out.put("path", "engine");
            return out;
        }
        out.put("periodEnd", point(x, "END_DATE", "PERIOD_END", "END_VERSION", "END_CAPTURE"));
        out.put("average", num(x.get("AVERAGE")));
        out.put("high", point(x, "HIGH_DATE", "HIGH", "HIGH_VERSION", "HIGH_CAPTURE"));
        out.put("low", point(x, "LOW_DATE", "LOW", "LOW_VERSION", "LOW_CAPTURE"));
        List<String> missing = new ArrayList<>(dates(x.get("MISSING")));
        LocalDate first = store.dates().first();
        if (first.isAfter(period.from())) {
            missing.add(0, "before " + first + ": no official record at all (the records start " + first + ")");
        }
        out.put("missing", missing);
        if ("var".equals(measure)) {
            String upTo = String.valueOf(x.get("END_DATE"));
            int took = ((Number) x.get("AVERAGE_N_RECORDS")).intValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("days", averageDays);
            m.put("records", took);
            m.put("from", x.get("AVERAGE_N_FROM") == null ? null : String.valueOf(x.get("AVERAGE_N_FROM")));
            m.put("to", upTo);
            m.put("average", x.get("AVERAGE_N") == null ? null : num(x.get("AVERAGE_N")));
            if (took < averageDays) {
                m.put("note", "only " + took + " official records to " + upTo + ", of the " + averageDays + " the average takes");
            }
            out.put("average" + averageDays, m);
            List<String> used = dates(x.get("STRESSED_DATES"));
            List<String> without = dates(x.get("WITHOUT_STRESSED"));
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("weeks", stressWeeks);
            st.put("from", String.valueOf(x.get("STRESS_FROM")));
            st.put("to", upTo);
            st.put("records", used.size());
            st.put("average", x.get("STRESSED_AVERAGE") == null ? null : num(x.get("STRESSED_AVERAGE")));
            st.put("dates", used);
            st.put("withoutStressedVar", without);
            st.put("note", used.isEmpty() ? "no official stressed VaR of this node in the " + stressWeeks + " weeks to " + upTo
                    : "the mean of the official stressed VaR of " + used.size() + " business date" + (used.size() == 1 ? "" : "s") + " in the "
                    + stressWeeks + " weeks to " + upTo + (without.isEmpty() ? "" : "; " + without.size() + " official date"
                    + (without.size() == 1 ? "" : "s") + " without one left out"));
            out.put("stressedAverage", st);
        }
        out.put("path", "engine");
        return out;
    }

    /**
     * The regulatory backtest's figures through the engine ({@code GET /backtesting}, {@code path=engine}, decision 6.6 (5)): the engine's
     * {@code backtest} and {@code backtest-days} over {@code var-history} (the published records, which carry the VaR at the backtest's
     * other confidence since contract 1.6) on the newest official date to the one asked, every node, for {@link VarBacktesting} to shape
     * as in-tree. Refused in words when the engine's records are not the store's (publish off, a session opened before a capture), when
     * the period holds a synthetic demo history (never published), or when a tested day's record was published without the VaR at a
     * backtest confidence (before contract 1.6): those dates are named.
     */
    VarBacktesting.Engine backtesting(Principal p, LocalDate last, VarBacktesting held, List<Double> confidences, int window) {
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the backtest is answered nowhere");
        }
        VarOfficialStore store = svc.official();
        List<LocalDate> span = new ArrayList<>(store.dates().headSet(last, true));
        for (LocalDate d : span.subList(Math.max(0, span.size() - (window + 31)), span.size())) {
            Map<String, Object> rec = store.index(d).orElse(null);
            if (rec != null && Boolean.TRUE.equals(rec.get("synthetic"))) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the official records to " + last + " hold a synthetic demo history (" + d
                        + "), which is never published: the engine reads the published records; ask it in-tree (path=in-tree)");
            }
        }
        Map<String, Object> a = ask(p, List.of("backtest", "backtest-days"), new LinkedHashMap<>(Map.of("node", "")),
                Map.of("date", last.toString()));
        Map<String, Map<Double, Map<String, Object>>> tests = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(a, "backtest")) {
            tests.computeIfAbsent(String.valueOf(r.get("NODE_ID")), k -> new java.util.TreeMap<>()).put(num(r.get("CONFIDENCE")), r);
        }
        // node -> confidence index -> date -> the tested day's row
        Map<String, List<Map<LocalDate, Map<String, Object>>>> days = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(a, "backtest-days")) {
            int k = -1;
            for (int i = 0; i < confidences.size(); i++) {
                k = Math.abs(confidences.get(i) - num(r.get("CONFIDENCE"))) < 1e-9 ? i : k;
            }
            if (k < 0) {
                continue;                                                   // a confidence not configured here
            }
            List<Map<LocalDate, Map<String, Object>>> byQ = days.computeIfAbsent(String.valueOf(r.get("NODE_ID")), x -> {
                List<Map<LocalDate, Map<String, Object>>> l = new ArrayList<>();
                confidences.forEach(q -> l.add(new LinkedHashMap<>()));
                return l;
            });
            byQ.get(k).put(LocalDate.parse(String.valueOf(r.get("DATE")).substring(0, 10)), r);
        }
        Map<String, List<VarBacktesting.DayRow>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<LocalDate, Map<String, Object>>>> e : days.entrySet()) {
            String node = e.getKey();
            List<VarBacktesting.DayRow> list = new ArrayList<>();
            List<String> without = new ArrayList<>();
            for (Map.Entry<LocalDate, Map<String, Object>> d : e.getValue().get(0).entrySet()) {
                Map<String, Object> r0 = d.getValue();
                LocalDate prior = LocalDate.parse(String.valueOf(r0.get("PRIOR")).substring(0, 10));
                Map<?, ?> stored = store.index(prior).orElse(null) instanceof Map<?, ?> rec && rec.get("nodes") instanceof Map<?, ?> n
                        && n.get(node) instanceof Map<?, ?> f ? f : null;
                double[] var = new double[confidences.size()];
                for (int k = 0; k < confidences.size(); k++) {
                    Map<String, Object> rk = e.getValue().get(k).get(d.getKey());
                    var[k] = rk == null ? Double.NaN : num(rk.get("VAR"));
                    double kept = stored == null ? Double.NaN : held.varAt(stored, confidences.get(k));
                    if (Double.isFinite(kept) && rk == null) {
                        without.add(prior.toString());                      // the store keeps it, the published record does not
                    } else if (Double.isFinite(kept) && Double.doubleToLongBits(kept) != Double.doubleToLongBits(var[k])
                            || stored == null && k == 0) {
                        throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " reads the official VaR of " + node + " on "
                                + prior + " as " + var[k] + ", the official store " + kept + " (published: drishti.var.official.publish; a "
                                + "session opened before a capture keeps the records it read): ask it in-tree (path=in-tree)");
                    }
                }
                Object rtpl = r0.get("RTPL");
                list.add(new VarBacktesting.DayRow(d.getKey(), prior, var, num(r0.get("ACTUAL")), num(r0.get("HYPOTHETICAL")),
                        rtpl == null ? Double.NaN : num(rtpl), false));
            }
            if (!without.isEmpty()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the published official records of " + node + " on " + String.join(", ",
                        new java.util.TreeSet<>(without)) + " carry no VaR at every backtest confidence " + confidences + " (published "
                        + "before contract 1.6 added BACKTEST_VAR): the engine path does not test those dates; ask it in-tree (path=in-tree)");
            }
            out.put(node, list);
        }
        return new VarBacktesting.Engine(out, tests);
    }

    private static Map<String, Object> point(Map<String, Object> x, String date, String value, String version, String capture) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", String.valueOf(x.get(date)));
        m.put("value", num(x.get(value)));
        m.put("resultVersion", x.get(version) == null ? null : ((Number) x.get(version)).longValue());
        m.put("captureId", x.get(capture));
        return m;
    }

    /** A comma-separated list of dates (empty: none). */
    private static List<String> dates(Object text) {
        String t = text == null ? "" : String.valueOf(text);
        return t.isEmpty() ? List.of() : List.of(t.split(","));
    }

    /** The node a scope names (a node the caller may ask whole: its parametric figures are node-pvar's), or null for any other scope. */
    String node(Principal p, String scope, LocalDate date, String ccy) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        return resolve(p, index(p, date, VarCurrencies.normalise(ccy)), scope, date).node();
    }

    /** The canonical form of a scope as Drishti's resolver writes it over the engine's index of the date (the reporting currency's). */
    String canonical(Principal p, String scope, LocalDate date) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        return resolve(p, index(p, date, VarCurrencies.normalise(null)), scope, date).canonical();
    }

    /** A node's name as the tree and the headline's label show it (the reference entity's name when the level has one). */
    String nodeName(Principal p, LocalDate date, String currency, String node) {
        VarService svc = service.getIfAvailable();
        EngineScopeBook book = index(p, date, currency);
        return svc == null ? book.names.getOrDefault(node, node)
                : nodeName(book, svc.loader().names(com.ash.drishti.api.AsOf.of(date), svc.hierarchies()), node);
    }

    static String nodeName(EngineScopeBook book, com.ash.drishti.banking.compute.var.Cells.Names names, String c) {
        String value = c.substring(Math.max(c.lastIndexOf('/'), c.lastIndexOf('~')) + 1);
        String named = book.levels.get(c) == null ? null : names.of(book.levels.get(c), value);
        return named != null ? named : book.names.getOrDefault(c, c);
    }

    /** A scope's VaR and ES and its child-node parts' rows (with the raw standalone and component VaR under {@code _alone}, {@code _comp}). */
    record Parts(double var, double es, List<Map<String, Object>> rows) {}

    Parts nodeParts(Principal p, EngineScopeBook book, ScopeResolver.Resolution res, LocalDate date, String currency) {
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the parts are answered nowhere");
        }
        List<String> kids = res.node() == null ? List.of() : book.children.getOrDefault(res.node(), List.of());
        if (kids.isEmpty()) {
            return positionParts(p, svc, book, res, date, currency, true);
        }
        Map<String, Object> params = params(dateWindow(), currency);
        params.put("rows", ordinals(res.set()));
        params.put("node", res.node());
        List<Integer> all = new ArrayList<>();
        List<String> named = new ArrayList<>();
        com.ash.drishti.banking.compute.var.Cells.Names names = svc.loader().names(com.ash.drishti.api.AsOf.of(date), svc.hierarchies());
        for (String c : kids) {
            all.addAll(ordinals(book.members(c).and(res.set())));
            all.add(-1);
            named.add(c);
        }
        params.put("parts", all);
        params.put("part-nodes", String.join(",", named));
        Map<String, Object> a = ask(p, List.of("scope-var", "scope-contributions"), params, Map.of("date", date.toString()));
        List<Map<String, Object>> sv = rows(a, "scope-var");
        if (sv.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no VaR for " + res.canonical() + " on "
                    + date + missing(a));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rows(a, "scope-contributions")) {
            String c = named.get(((Number) r.get("PART")).intValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c);
            m.put("scope", "NODE:" + c);
            m.put("label", nodeName(book, names, c));
            m.put("kind", book.levels.get(c));
            double alone = num(r.get("STANDALONE_VAR"));
            double comp = num(r.get("COMPONENT_VAR"));
            m.put("standaloneVar", VarViews.round(alone));
            m.put("standaloneEs", VarViews.round(num(r.get("STANDALONE_ES"))));
            m.put("componentVar", VarViews.round(comp));
            m.put("componentEs", VarViews.round(num(r.get("COMPONENT_ES"))));
            m.put("pct", num(r.get("PCT")));
            m.put("incrementalVar", VarViews.round(num(r.get("INCREMENTAL_VAR"))));
            m.put("marginalPer1m", null);
            m.put("level", book.levels.get(c));
            m.put("_alone", alone);
            m.put("_comp", comp);
            rows.add(m);
        }
        return new Parts(num(sv.get(0).get("VAR")), num(sv.get(0).get("ES")), rows);
    }

    /** The host's position parts of a scope: each in-tree part without its vector (labels, size, attributes) and its rows in the engine's index. */
    record HostPositions(List<VarViews.Part> parts, List<List<Integer>> rows) {}

    /**
     * The positions of a leaf node as the in-tree route lists them (R2 lane G part 6): the host's own data gives each part's key, label,
     * size and attributes ({@link VarViews#position(com.ash.drishti.banking.compute.var.VarBook, OrdinalSet, int, boolean)} without the
     * vector), in the in-tree order ({@link VarPartTotals#positionRuns}), masked as in-tree; each part's rows are its position's rows of the
     * engine's index the scope holds. Empty for a viewer who sees the node in aggregate only (as in-tree: no parts). More positions than
     * {@code max-parts} when {@code cut} (the host's cut with the totals over all parts: the headline asks every one uncut) and a scope that is not a node are refused in words for now.
     */
    HostPositions hostPositions(Principal p, VarService svc, EngineScopeBook book, ScopeResolver.Resolution res, LocalDate date, String currency,
            boolean cut) {
        if (res.node() == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers the parts of a node for now; the parts of "
                    + res.canonical() + " are positions, trades or terms: ask it in-tree (path=in-tree)");
        }
        VarService.Scoped s = svc.scoped(p, "NODE:" + res.node(), date, currency);
        com.ash.drishti.banking.compute.var.VarBook b = s.date().book();
        if (!s.view().seesPositions(s.set())) {
            return new HostPositions(List.of(), List.of());          // an aggregate-only viewer: no position level below this node
        }
        List<int[]> runs = VarPartTotals.positionRuns(b, s.set());
        if (cut && runs.size() > svc.props().maxParts()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers up to max-parts (" + svc.props().maxParts() + ") positions "
                    + "for now; " + res.canonical() + " holds " + runs.size() + ": ask it in-tree (path=in-tree)");
        }
        List<VarViews.Part> parts = new ArrayList<>(runs.size());
        List<List<Integer>> rows = new ArrayList<>(runs.size());
        for (int[] r : runs) {
            VarViews.Part pt = VarViews.position(b, s.set(), r[0], false);
            int e = book.positionIndexAnyCase(pt.key());
            if (e < 0) {
                throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " has no position " + pt.key() + " on " + date
                        + ": ask it in-tree (path=in-tree)");
            }
            List<Integer> own = new ArrayList<>();
            book.forEachPositionRun(e, (from, to) -> {
                for (int o = from; o < to; o++) {
                    if (res.set().contains(o)) {
                        own.add(o);
                    }
                }
            });
            Map<String, Object> attrs = new LinkedHashMap<>();
            pt.attrs().forEach((k, v) -> attrs.put(k, v != null && svc.access().masked(p, k) ? com.ash.drishti.api.DataNode.MASK : v));
            parts.add(new VarViews.Part(pt.key(), pt.scope(), pt.label(), pt.kind(), null, pt.size(), attrs));
            rows.add(own);
        }
        return new HostPositions(parts, rows);
    }

    /** A leaf node's position parts measured by the engine's {@code scope-contributions}, the rows shaped as the in-tree route's. */
    private Parts positionParts(Principal p, VarService svc, EngineScopeBook book, ScopeResolver.Resolution res, LocalDate date, String currency,
            boolean cut) {
        HostPositions hp = hostPositions(p, svc, book, res, date, currency, cut);
        Map<String, Object> params = params(dateWindow(), currency);
        params.put("rows", ordinals(res.set()));
        params.put("node", res.node());
        List<Integer> all = new ArrayList<>();
        for (List<Integer> r : hp.rows()) {
            all.addAll(r);
            all.add(-1);
        }
        params.put("parts", all);
        List<String> outputs = hp.parts().isEmpty() ? List.of("scope-var") : List.of("scope-var", "scope-contributions");
        Map<String, Object> a = ask(p, outputs, params, Map.of("date", date.toString()));
        List<Map<String, Object>> sv = rows(a, "scope-var");
        if (sv.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no VaR for " + res.canonical() + " on "
                    + date + missing(a));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : hp.parts().isEmpty() ? List.<Map<String, Object>>of() : rows(a, "scope-contributions")) {
            VarViews.Part pt = hp.parts().get(((Number) r.get("PART")).intValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", pt.key());
            m.put("scope", pt.scope());
            m.put("label", pt.label());
            m.put("kind", pt.kind());
            double alone = num(r.get("STANDALONE_VAR"));
            double comp = num(r.get("COMPONENT_VAR"));
            m.put("standaloneVar", VarViews.round(alone));
            m.put("standaloneEs", VarViews.round(num(r.get("STANDALONE_ES"))));
            m.put("componentVar", VarViews.round(comp));
            m.put("componentEs", VarViews.round(num(r.get("COMPONENT_ES"))));
            m.put("pct", num(r.get("PCT")));
            m.put("incrementalVar", VarViews.round(num(r.get("INCREMENTAL_VAR"))));
            // the in-tree calculator's marginal: component VaR per million of the part's size (VarCalc.contributions), the host's size
            double marginal = Double.isNaN(pt.size()) || pt.size() == 0 ? Double.NaN : comp / Math.abs(pt.size()) * 1_000_000;
            m.put("marginalPer1m", Double.isNaN(marginal) ? null : VarViews.round(marginal));
            m.putAll(pt.attrs());
            if (!pt.attrs().containsKey("level")) {
                m.put("level", pt.kind());
            }
            m.put("_alone", alone);
            m.put("_comp", comp);
            rows.add(m);
        }
        return new Parts(num(sv.get(0).get("VAR")), num(sv.get(0).get("ES")), rows);
    }

    /**
     * A pre-deal what-if through the engine ({@code POST /what-if}): the engine's {@code what-if} on a node's stored aggregate, each item's
     * vector supplied by the host (a copy of a booked trade's or any scope's summed P&amp;L, {@code scope-var}, or a P&amp;L priced
     * elsewhere), its factor applied by the engine; shaped as the in-tree route ({@code before}, {@code after}, {@code change},
     * {@code items}, {@code limit}). A scope that is not a node is its row set ({@code rows}, summed as scope-var sums it; a system
     * basket's own limit); a currency other than USD (the engine's vectors are USD) is answered in-tree for now, said in one sentence.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> whatIf(Principal p, Map<String, Object> body, LocalDate date) {
        if (date == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path needs the business date: give asOf");
        }
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the what-if is answered nowhere");
        }
        String scope = body.get("scope") == null ? "" : String.valueOf(body.get("scope")).trim();
        if (scope.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if names its scope: {\"scope\": \"DESK:RATES\", \"items\": [{\"copyOf\": \"MX-30000121\", \"factor\": -1}]}");
        }
        String currency = VarCurrencies.normalise(body.get("ccy") == null ? null : String.valueOf(body.get("ccy")));
        if (!currency.equals("USD")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers a what-if in USD for now: ask " + currency + " in-tree (path=in-tree)");
        }
        List<Map<String, Object>> asked = body.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (asked.isEmpty() || asked.size() > 50) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if has 1 to 50 items: [{\"copyOf\": <a booked trade>, \"factor\": 1}]");
        }
        int window = dateWindow();
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, scope, date);
        if (res.node() == null && res.set().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, res.canonical() + " holds no trades on " + date + ": nothing to add the items to");
        }
        List<Map<String, Object>> trades = new ArrayList<>();
        for (Map<String, Object> it : asked) {
            String from = it.get("copyOf") == null ? "" : String.valueOf(it.get("copyOf")).trim();
            boolean priced = it.get("pnl") instanceof List<?>;
            if (from.isEmpty() && !priced) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if item is a copy of a booked trade, {\"copyOf\": \"MX-30000121\", \"factor\": 1}, "
                        + "or a P&L priced elsewhere, {\"label\": \"new swap\", \"pnl\": [one per scenario day], \"factor\": 1}");
            }
            double f;
            try {
                f = it.get("factor") instanceof Number n ? n.doubleValue() : it.get("factor") == null ? 1.0 : Double.parseDouble(String.valueOf(it.get("factor")));
            } catch (NumberFormatException e) {
                f = Double.NaN;
            }
            if (!Double.isFinite(f)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "a what-if factor is a finite number (-1: the unwind), not " + it.get("factor"));
            }
            List<Double> v = new ArrayList<>();
            if (priced) {
                for (Object x : (List<?>) it.get("pnl")) {
                    if (!(x instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                        throw new DrishtiException(ErrorCode.BAD_REQUEST, "what-if item " + (it.get("label") == null ? "" : it.get("label") + " ")
                                + "has a P&L that is not a number on scenario day " + (v.size() + 1));
                    }
                    v.add(n.doubleValue());
                }
                from = it.get("label") == null ? "a trade priced elsewhere" : String.valueOf(it.get("label"));
            } else {
                String text = from.contains(":") ? from : "TRD:" + from;
                ScopeResolver.Resolution t = resolve(p, book, text, date);
                if (t.set().isEmpty()) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, text + " has no P&L vector on " + date);
                }
                Map<String, Object> cp = params(window, currency);
                cp.put("rows", ordinals(t.set()));
                cp.put("node", t.node() == null ? "" : t.node());
                List<Map<String, Object>> cr = rows(ask(p, List.of("scope-var"), cp, Map.of("date", date.toString())), "scope-var");
                if (cr.isEmpty()) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, text + " has no P&L vector on " + date);
                }
                for (double x : doubles(cr.get(0).get("PNL"))) {
                    v.add(x);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("LABEL", it.get("label") == null ? (f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f)) + " × " + from
                    : String.valueOf(it.get("label")));
            row.put("FACTOR", f);
            row.put("PNL", v);
            trades.add(row);
        }
        Map<String, Object> params = params(window, currency);
        if (res.node() != null) {
            params.put("node", res.node());
        } else {
            params.put("rows", ordinals(res.set()));                       // any other scope: its row set, summed as scope-var sums it
        }
        params.put("trades", trades);
        Map<String, Object> a = ask(p, List.of("what-if"), params, Map.of("date", date.toString()));
        Map<String, Object> b = row(a, "what-if", "ROW", "BEFORE");
        Map<String, Object> af = row(a, "what-if", "ROW", "AFTER");
        if (b == null || af == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no what-if for " + res.canonical() + " on "
                    + date + missing(a));
        }
        double bv = num(b.get("VAR"));
        double av = num(af.get("VAR"));
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        Map<String, Object> change = new LinkedHashMap<>();
        before.put("var", whole(bv));
        after.put("var", whole(av));
        change.put("var", whole(num(af.get("INCREMENTAL_VAR"))));
        before.put("es", whole(num(b.get("ES"))));
        after.put("es", whole(num(af.get("ES"))));
        change.put("es", whole(num(af.get("ES")) - num(b.get("ES"))));
        before.put("stressedVar", whole(num(b.get("STRESSED_VAR"))));
        after.put("stressedVar", whole(num(af.get("STRESSED_VAR"))));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows(a, "what-if")) {
            String id = String.valueOf(r.get("ROW"));
            if (!id.startsWith("TRADE:")) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("label", id.substring("TRADE:".length()));
            x.put("factor", num(r.get("FACTOR")));
            x.put("standaloneVar", whole(num(r.get("VAR"))));
            x.put("incrementalVar", whole(num(r.get("INCREMENTAL_VAR"))));
            x.put("incrementalAlone", whole(num(r.get("INCREMENTAL_ALONE"))));
            x.put("componentVar", whole(num(r.get("COMPONENT_VAR"))));
            items.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", res.canonical());
        out.put("businessDate", date.toString());
        out.put("currency", currency);
        out.put("confidence", svc.calc().confidence());
        out.put("esConfidence", svc.calc().esConfidence());
        out.put("before", before);
        out.put("after", after);
        out.put("change", change);
        out.put("items", items);
        String port = res.node() == null && res.canonical().startsWith("PORT:") ? res.canonical().substring(5) : null;   // a system basket's own limit
        double limit = res.node() != null ? book.nodeLimit(res.node()) : port != null ? book.basketLimits.getOrDefault(port, Double.NaN) : Double.NaN;
        if (Double.isNaN(limit) || limit == 0 || !svc.loader().limitBasis(date, res.node() != null ? res.node() : port).matches(svc.props().defaultLens())) {
            out.put("limit", null);
        } else {
            com.ash.drishti.banking.RiskLimits lim = limits.getIfAvailable(com.ash.drishti.banking.RiskLimits::defaults);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("limit", VarViews.round(limit));
            m.put("useBefore", bv / limit);
            m.put("useAfter", av / limit);
            m.put("statusBefore", lim.status(bv / limit));
            m.put("status", lim.status(av / limit));
            m.put("near", lim.limitNear());
            out.put("limit", m);
        }
        out.put("engine", ENGINE);
        out.put("varExact", av);
        out.put("path", "engine");
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static Long whole(double x) {
        return Double.isFinite(x) ? Math.round(x) : null;
    }

    private static Map<String, Object> scenarioRow(List<String> dates, int j, int rank, double pnl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", rank);
        m.put("index", j);
        m.put("date", dates.get(j));
        m.put("pnl", VarViews.round(pnl));
        return m;
    }

    private static List<Integer> ordinals(com.ash.drishti.compute.vector.OrdinalSet set) {
        List<Integer> rows = new ArrayList<>(set.size());
        set.forEach(rows::add);
        return rows;
    }

    private static double[] doubles(Object list) {
        if (list instanceof double[] d) {
            return d;
        }
        java.util.Collection<?> c = list instanceof java.util.Collection<?> l ? l : List.of();
        double[] out = new double[c.size()];
        int i = 0;
        for (Object o : c) {
            out[i++] = ((Number) o).doubleValue();
        }
        return out;
    }

    private static int[] ints(Object list) {
        double[] d = doubles(list);
        int[] out = new int[d.length];
        for (int i = 0; i < d.length; i++) {
            out[i] = (int) d[i];
        }
        return out;
    }

    /**
     * A node's parametric figures from the engine's {@code node-pvar} (R2_CUT_PLAN 6.6 (4): within relative 1e-12 of in-tree, the normal
     * quantile's last bits; historical VaR and ES stay bit-exact): the figures as Drishti's {@code ParametricVar.Figures} (the block and
     * the tooltip are shaped by {@link VarParametric} as in-tree), the historical rank {@code k}, the scenario set and its newest day.
     */
    record Parametric(com.ash.drishti.banking.compute.var.ParametricVar.Figures figures, int k, String set, String lastDay) {}

    /**
     * Every node's parametric figures on a window in a currency ({@code node-pvar}, one ask), keyed by node id; the fit's weights are
     * not carried (no block reads them). {@code confidence} and {@code esConfidence} are the lens's (the engine's configured ones).
     */
    Map<String, Parametric> parametric(Principal p, LocalDate date, String currency, int window, double confidence, double esConfidence,
            com.ash.drishti.banking.compute.var.ParametricVar.Settings settings) {
        return parametricRows(ask(p, List.of("node-pvar"), params(window, currency), Map.of("date", date.toString())), confidence, esConfidence,
                settings);
    }

    private static Map<String, Parametric> parametricRows(Map<String, Object> a, double confidence, double esConfidence,
            com.ash.drishti.banking.compute.var.ParametricVar.Settings settings) {
        Map<String, Parametric> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(a, "node-pvar")) {
            double sigma = num(r.get("SIGMA"));
            double z = num(r.get("Z"));
            var fit = new com.ash.drishti.banking.compute.var.ParametricVar.Fit(((Number) r.get("SCENARIOS")).intValue(), num(r.get("MU")),
                    sigma, num(r.get("SKEW")), num(r.get("EXCESS_KURTOSIS")), new double[0], settings);
            var f = new com.ash.drishti.banking.compute.var.ParametricVar.Figures(fit, confidence, esConfidence, z, num(r.get("Z_ES")),
                    num(r.get("ES_FACTOR")), num(r.get("PVAR")), num(r.get("PES")), z * sigma, ((Number) r.get("BEYOND")).intValue());
            out.put(String.valueOf(r.get("NODE_ID")), new Parametric(f, ((Number) r.get("K")).intValue(), String.valueOf(r.get("SCENARIO_SET")),
                    r.get("LAST_DAY") == null ? null : String.valueOf(r.get("LAST_DAY"))));
        }
        return out;
    }

    /**
     * The parametric components of a node's children on the date's window in a currency ({@code node-pvar-components}), keyed by child id:
     * what the contributions' parametric columns are made of (the in-tree route's {@code ParametricVar.component} of each child in the node).
     */
    Map<String, com.ash.drishti.banking.compute.var.ParametricVar.Component> pComponents(Principal p, LocalDate date, String currency, String node) {
        Map<String, Object> a = ask(p, List.of("node-pvar-components"), params(dateWindow(), currency), Map.of("date", date.toString()));
        Map<String, com.ash.drishti.banking.compute.var.ParametricVar.Component> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(a, "node-pvar-components")) {
            if (node.equals(String.valueOf(r.get("NODE_ID")))) {
                out.put(String.valueOf(r.get("CHILD")), new com.ash.drishti.banking.compute.var.ParametricVar.Component(num(r.get("MU")),
                        num(r.get("SIGMA")), num(r.get("COV")), num(r.get("RHO")), num(r.get("COMPONENT_PVAR")), num(r.get("COMPONENT_PES")),
                        num(r.get("STANDALONE_PVAR")), num(r.get("INCREMENTAL_PVAR"))));
            }
        }
        return out;
    }

    /** A position part's parametric component from the engine and the host's size of it (its market value, NaN without one). */
    record PositionComponent(com.ash.drishti.banking.compute.var.ParametricVar.Component component, double size) {}

    /**
     * The parametric components of a leaf node's positions (the contributions' parametric columns, R2 lane G part 6): the host's parts
     * ({@link #hostPositions}) measured by the engine's {@code scope-pcomponents} against the node's own fit, keyed by position id.
     */
    Map<String, PositionComponent> pPositionComponents(Principal p, LocalDate date, String currency, String node) {
        VarService svc = service.getIfAvailable();
        if (svc == null) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the VaR service is not configured here: the parts are answered nowhere");
        }
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, "NODE:" + node, date);
        HostPositions hp = hostPositions(p, svc, book, res, date, currency, true);
        Map<String, PositionComponent> out = new LinkedHashMap<>();
        if (hp.parts().isEmpty()) {
            return out;
        }
        Map<String, Object> params = params(dateWindow(), currency);
        params.put("rows", ordinals(res.set()));
        params.put("node", node);
        List<Integer> all = new ArrayList<>();
        for (List<Integer> r : hp.rows()) {
            all.addAll(r);
            all.add(-1);
        }
        params.put("parts", all);
        for (Map<String, Object> r : rows(ask(p, List.of("scope-pcomponents"), params, Map.of("date", date.toString())), "scope-pcomponents")) {
            VarViews.Part pt = hp.parts().get(((Number) r.get("PART")).intValue());
            out.put(pt.key(), new PositionComponent(new com.ash.drishti.banking.compute.var.ParametricVar.Component(num(r.get("MU")),
                    num(r.get("SIGMA")), num(r.get("COV")), num(r.get("RHO")), num(r.get("COMPONENT_PVAR")), num(r.get("COMPONENT_PES")),
                    num(r.get("STANDALONE_PVAR")), num(r.get("INCREMENTAL_PVAR"))), pt.size()));
        }
        return out;
    }

    /**
     * Every node's parametric figures through a lens (a horizon or the stress set: {@code node-pvar} on the lens's vectors, the configured
     * confidences, the date's window), keyed by node id; the 1-day normal lens is {@link #parametric(Principal, LocalDate, String, int,
     * double, double, com.ash.drishti.banking.compute.var.ParametricVar.Settings)}.
     */
    Map<String, Parametric> parametric(Principal p, LocalDate date, String currency, com.ash.drishti.banking.compute.var.VarLens lens,
            com.ash.drishti.banking.compute.var.ParametricVar.Settings settings) {
        Map<String, Object> params = params(dateWindow(), currency);
        params.put("stressed", lens.stressed());
        params.put("horizon", lens.tenDay() ? "10d" : "1d");
        params.put("ten-day-method", lens.method() == com.ash.drishti.banking.compute.var.VarLens.Method.SUM ? "sum" : "sqrt10");
        lensConfidences(params, lens);
        return parametricRows(ask(p, List.of("node-pvar"), params, Map.of("date", date.toString())), lens.confidence(), lens.esConfidence(),
                settings);
    }

    /** A lens's confidences when they are not the configured ones (an offered confidence, lane G part 6): the engine's lens-* params. */
    private void lensConfidences(Map<String, Object> params, com.ash.drishti.banking.compute.var.VarLens lens) {
        VarService svc = service.getIfAvailable();
        com.ash.drishti.banking.compute.var.VarLens d = svc == null ? null : svc.props().defaultLens();
        if (d == null || lens.confidence() != d.confidence()) {
            params.put("lens-confidence", lens.confidence());
        }
        if (d == null || lens.esConfidence() != d.esConfidence()) {
            params.put("lens-es-confidence", lens.esConfidence());
        }
    }

    /** A node's parts' matrix through the engine: the parts' keys and labels in the in-tree order, {covariance, correlation}, the algebra. */
    record PMatrix(List<String> keys, List<String> labels, double[][][] cm, boolean truncated, String engine) {}

    /**
     * The parametric matrix of a node's child nodes (the contributions' {@code matrix=true}): the first {@code max} parts in the hierarchy's
     * order, each the child's rows in the scope, from the engine's {@code scope-pmatrix}.
     */
    PMatrix pMatrix(Principal p, String scope, LocalDate date, String ccy, int max) {
        VarService svc = service.getIfAvailable();
        String currency = VarCurrencies.normalise(ccy);
        EngineScopeBook book = index(p, date, currency);
        ScopeResolver.Resolution res = resolve(p, book, scope, date);
        List<String> kids = res.node() == null ? List.of() : book.children.getOrDefault(res.node(), List.of());
        if (kids.isEmpty() && res.node() != null) {                   // a leaf node: its parts are positions (lane G part 6)
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine path answers the matrix of a node's child nodes; the matrix of a leaf "
                    + "node's positions (" + res.canonical() + "): ask it in-tree (path=in-tree)");
        }
        if (kids.size() < 2) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "matrix=true needs a scope with parts (" + res.canonical() + " has " + kids.size() + ")");
        }
        boolean truncated = kids.size() > max;
        List<String> shown = truncated ? kids.subList(0, max) : kids;
        com.ash.drishti.banking.compute.var.Cells.Names names = svc.loader().names(com.ash.drishti.api.AsOf.of(date), svc.hierarchies());
        List<Integer> all = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String c : shown) {
            all.addAll(ordinals(book.members(c).and(res.set())));
            all.add(-1);
            labels.add(nodeName(book, names, c));
        }
        Map<String, Object> params = params(dateWindow(), currency);
        params.put("parts", all);
        params.put("part-nodes", String.join(",", shown));
        Map<String, Object> a = ask(p, List.of("scope-pmatrix"), params, Map.of("date", date.toString()));
        int k = shown.size();
        double[][][] cm = new double[2][k][k];
        String engine = null;
        int n = 0;
        for (Map<String, Object> r : rows(a, "scope-pmatrix")) {
            int i = ((Number) r.get("I")).intValue();
            int j = ((Number) r.get("J")).intValue();
            cm[0][i][j] = num(r.get("COVARIANCE"));
            cm[1][i][j] = num(r.get("CORRELATION"));
            engine = String.valueOf(r.get("ENGINE"));
            n++;
        }
        if (n != k * k) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + ENGINE + " answered " + n + " cells of the " + k + " x " + k
                    + " matrix of " + res.canonical() + missing(a));
        }
        List<String> keys = new ArrayList<>(kids);
        List<String> allLabels = new ArrayList<>(labels);
        for (String c : kids.subList(shown.size(), kids.size())) {
            allLabels.add(nodeName(book, names, c));
        }
        return new PMatrix(keys, allLabels, cm, truncated, engine);
    }

    /** The window a headline's figures are on: the asked one cut to the date's, or the date's (0: the whole set). */
    int window(String window) {
        int dateWindow = dateWindow();
        int asked = window == null || window.isBlank() || window.equalsIgnoreCase("all") ? 0 : Integer.parseInt(window.trim());
        return dateWindow == 0 ? asked : asked == 0 || asked > dateWindow ? dateWindow : asked;
    }

    /** One scope's figures on one window: VaR, ES, the rows counted and the limit (NaN when none) in the answer's currency. */
    record Figures(double var, double es, long members, double limit) {}

    private Figures nodeFigures(Principal p, String node, LocalDate date, String currency, int window) {
        Map<String, Object> a = ask(p, List.of("node-var"), params(window, currency), Map.of("date", date.toString()));
        Map<String, Object> row = row(a, "node-var", "NODE_ID", node);
        if (row == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no node " + node + " on " + date + missing(a));
        }
        return new Figures(((Number) row.get("VAR")).doubleValue(), ((Number) row.get("ES")).doubleValue(), ((Number) row.get("MEMBERS")).longValue(),
                row.get("LIMIT") == null ? Double.NaN : ((Number) row.get("LIMIT")).doubleValue());
    }

    private Figures rowFigures(Principal p, ScopeResolver.Resolution res, LocalDate date, String currency, int window, double limit) {
        Map<String, Object> params = params(window, currency);
        List<Integer> rows = new ArrayList<>(res.set().size());
        res.set().forEach(rows::add);
        params.put("rows", rows);
        params.put("node", "");
        Map<String, Object> a = ask(p, List.of("scope-var"), params, Map.of("date", date.toString()));
        List<Map<String, Object>> r = rows(a, "scope-var");
        if (r.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " answered no VaR for " + res.canonical() + " on " + date
                    + missing(a));
        }
        Map<String, Object> x = r.get(0);
        return new Figures(((Number) x.get("VAR")).doubleValue(), ((Number) x.get("ES")).doubleValue(), ((Number) x.get("MEMBERS")).longValue(),
                limit);
    }

    private static Map<String, Object> params(int window, String currency) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("window", window);
        params.put("currency", currency);
        return params;
    }

    /** The date's configured window ({@code drishti.module.banking.var.window}: a number, or {@code all}, 0). */
    int dateWindow() {
        String v = env.getProperty("drishti.module.banking.var.window", "250").trim();
        return v.equalsIgnoreCase("all") ? 0 : Integer.parseInt(v);
    }

    // ------------------------------------------------------------------------------------------------------------- scopes (EN-68)

    /** The engine's index of a date, kept while its inputs' stamp is the same (one date's index is a few rows per position). */
    private final Map<String, Object[]> indexes = new java.util.concurrent.ConcurrentHashMap<>();

    EngineScopeBook index(Principal p, LocalDate date, String currency) {
        Map<String, Object> words = ask(p, List.of("scope-words"), Map.of(), Map.of("date", date.toString()));
        Object stamp = stamp(words);
        String key = date + "|" + currency;                            // a rule's amounts are in the reporting currency
        Object[] have = indexes.get(key);
        if (have != null && java.util.Objects.equals(have[0], stamp)) {
            return (EngineScopeBook) have[1];
        }
        Map<String, Object> a = ask(p, List.of("scope-index", "scope-positions", "scope-position-values", "scope-nodes", "scope-words",
                "scope-limits"), params(dateWindow(), currency), Map.of("date", date.toString()));
        if (rows(a, "scope-index").isEmpty() && !missing(a).isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + ENGINE + " has no rows on " + date + missing(a));
        }
        EngineScopeBook b = new EngineScopeBook(a);
        if (indexes.size() > 4) {
            indexes.clear();                                            // a few dates kept, as the engine keeps one hot
        }
        indexes.put(key, new Object[] {stamp, b});
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Object stamp(Map<String, Object> a) {
        return a.get("stamp") instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("inputs") : null;
    }

    /** The scope as the in-tree route resolves it: the grammar, the system and saved baskets and captures are Drishti's. */
    ScopeResolver.Resolution resolve(Principal p, EngineScopeBook book, String scope, LocalDate date) {
        VarService svc = service.getIfAvailable();
        ScopeExpr parsed;
        ScopeResolver.Resolution res;
        try {
            parsed = ScopeExpr.parse(scope, book.words.keySet());
            res = new ScopeResolver(book, definitions(svc, p, date, book), book.words).resolve(scope);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (parsed instanceof ScopeExpr.Ref one && one.word().equals("PORT") && res.unknown().contains(one.toString())) {
            throw new DrishtiException(ErrorCode.VAR_SYSTEM_BASKET_UNKNOWN, "no system basket " + one.arg() + " on " + date
                    + ": PORT <GO> lists the system baskets (VAR_GUIDE.md section 5.7)");
        }
        if (svc != null && parsed.refs().stream().anyMatch(r -> r.word().equals("CPTY")) && svc.access().masked(p, "COUNTERPARTY")) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the counterparty is masked for your role: a scope may not select trades by it");
        }
        return res;
    }

    private static ScopeResolver.Definitions definitions(VarService svc, Principal p, LocalDate date, EngineScopeBook book) {
        return new ScopeResolver.Definitions() {
            @Override
            public Optional<String> portfolio(String id) {
                if (svc == null) {
                    return Optional.empty();
                }
                VarDate.Portfolio pf = svc.saved().system().portfolios(date, book.basketLimits).get(id);
                return pf == null ? Optional.empty() : Optional.of(pf.scope());
            }

            @Override
            public Optional<String> basket(String name) {
                return p == null || svc == null ? Optional.empty() : svc.saved().basket(p, name).map(b -> String.valueOf(b.get("scope")));
            }

            @Override
            public Optional<String> capture(String id) {
                return p == null || svc == null ? Optional.empty() : svc.saved().capture(p, id).map(c -> {
                    List<String> members = svc.members(c);
                    return members.isEmpty() ? "TRD:NONE" : "TRD:" + String.join(",", members);
                });
            }
        };
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
