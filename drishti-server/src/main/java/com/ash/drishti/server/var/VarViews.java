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
import com.ash.drishti.compute.var.Backtest;
import com.ash.drishti.compute.var.Columns;
import com.ash.drishti.compute.var.CurrencyTotals;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.var.VarMath;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The VaR engine's answers as the screens and the API return them (VAR_GUIDE.md sections 3 and 11): summaries, never a vector per
 * part. A headline with its provenance; the P&amp;L path as a histogram, the worst 25, the series and a page of the ranked table; the
 * contributions of the parts; the history with its backtest; the hierarchy with every node's figures.
 */
final class VarViews {

    /** The engine's version (the drishti-compute module's): stamped on answers and on the computed kinds. */
    static final String ENGINE_VERSION = versionOf();
    private static final String ENGINE = "drishti-compute " + ENGINE_VERSION;
    private final VarService svc;
    private final VarSelection selection;

    VarViews(VarService svc) {
        this.svc = svc;
        this.selection = new VarSelection(svc);
    }

    VarService service() {
        return svc;
    }

    /** The basket tray's terms, parts, notices and hints. */
    VarSelection selection() {
        return selection;
    }

    /** A part's standalone VaR: a term's is the term alone inside the scope (its vector holds only the trades allocated to it). */
    private double standalone(Part pt) {
        if (pt.attrs().get("alone") instanceof Number n) {
            return n.doubleValue();
        }
        return svc.calc().measures(pt.vector()).var();
    }

    /** The scenarios the date uses (VAR_GUIDE.md section 18.5): the newest {@code used} of the set's {@code of}, their dates, a warning. */
    Map<String, Object> scenarioWindow(VarDate d) {
        List<LocalDate> days = d.book().scenarioDates();
        int of = d.intake().setLength() > 0 ? d.intake().setLength() : days.size();
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("setting", svc.props().scenarioWindow());
        w.put("used", days.size());
        w.put("of", of);
        w.put("from", days.get(0).toString());
        w.put("to", days.get(days.size() - 1).toString());
        w.put("note", (days.size() == of ? "all " + of : "newest " + days.size() + " of " + of) + " scenarios (" + days.get(0) + " … "
                + days.get(days.size() - 1) + ")");
        int n = svc.props().window();
        if (n > of) {
            w.put("warning", "drishti.var.scenario-window is " + n + " but the scenario set has " + of + ": the whole set is used");
        }
        return w;
    }

    private static String versionOf() {
        String v = VarBook.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    // ------------------------------------------------------------------------------------------------------------------- headline

    Map<String, Object> headline(Principal p, String scope, LocalDate date) {
        return headline(p, scope, date, VarDate.USD);
    }

    /**
     * The headline's VaR and ES recomputed on the newest {@code window} scenarios (null: the date's) and at other confidences (null: the
     * settings'), from the same vector (VAR_GUIDE.md section 18.5): {@code override} says what changed; never official, never captured.
     */
    Map<String, Object> override(Map<String, Object> h, Principal p, String scope, LocalDate date, String ccy, String window, Double conf,
            Double esConf) {
        VarService.Scoped s = svc.scoped(p, scope, date, ccy);
        double[] v = s.vector();
        Map<String, Object> o = new LinkedHashMap<>();
        if (window != null && !window.equals("all")) {
            int n = Integer.parseInt(window);
            if (n < v.length) {
                v = java.util.Arrays.copyOfRange(v, v.length - n, v.length);
            } else {
                o.put("warning", "window " + n + " is more than the " + v.length + " scenarios loaded: all of them used");
            }
        }
        VarProperties pr = svc.props();
        double c = conf == null ? pr.confidence() : conf;
        double ec = esConf == null ? pr.esConfidence() : esConf;
        com.ash.drishti.compute.var.VarCalc calc = new com.ash.drishti.compute.var.VarCalc(c, ec, svc.calc().rule(), svc.calc().window(),
                svc.calc().scaleToVar());
        com.ash.drishti.compute.var.VarCalc.Measures m = calc.measures(v);
        List<LocalDate> days = s.date().book().scenarioDates();
        o.put("window", v.length);
        o.put("of", s.vector().length);
        o.put("from", days.get(days.size() - v.length).toString());
        o.put("to", days.get(days.size() - 1).toString());
        o.put("confidence", c);
        o.put("esConfidence", ec);
        o.put("official", false);
        o.put("note", "asked for this answer: the VaR and ES of the newest " + v.length + " of " + s.vector().length + " scenarios at "
                + Math.round(c * 1000) / 10.0 + "% (ES " + Math.round(ec * 1000) / 10.0 + "%); never official; the rest of the answer is the date's");
        Map<String, Object> out = new LinkedHashMap<>(h);
        out.put("var", round(m.var()));
        out.put("es", round(m.es()));
        out.put("k", m.k());
        out.put("esCount", m.esCount());
        out.put("scenarios", v.length);
        out.put("confidence", c);
        out.put("esConfidence", ec);
        out.put("override", o);
        return out;
    }

    /** The headline in a reporting currency ({@code ccy}; every amount of the answer is in it, and {@code currency} says which). */
    Map<String, Object> headline(Principal p, String scope, LocalDate date, String ccy) {
        VarService.Scoped s = svc.scoped(p, scope, date, ccy);
        VarBook b = s.date().book();
        List<Part> parts = parts(s, "child", p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("currency", s.date().currency());
        out.put("intraday", intradayNote(s.date()));
        out.put("canonical", s.res().canonical());
        out.put("kind", s.kind());
        out.put("label", s.label());
        out.put("node", s.res().node());
        out.put("businessDate", s.date().date().toString());
        out.put("var", round(s.measures().var()));
        out.put("es", round(s.measures().es()));
        out.put("k", s.measures().k());
        out.put("esCount", s.measures().esCount());
        out.put("scenarios", b.width());
        out.put("confidence", svc.props().confidence());
        out.put("esConfidence", svc.props().esConfidence());
        double standalone = 0;
        for (Part pt : parts) {
            standalone += standalone(pt);
        }
        out.put("trades", s.set().size());
        out.put("empty", s.set().isEmpty());
        if (s.kind().equals("adhoc") || s.kind().equals("basket")) {
            try {
                out.put("terms", VarSelection.describe(selection.terms(p, s.res().canonical())));
            } catch (IllegalArgumentException e) {
                out.put("terms", List.of());
            }
            if (s.kind().equals("basket") && p != null) {
                String id = s.res().canonical().substring("BASKET:".length());
                svc.saved().basket(p, id).ifPresent(bk -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", bk.get("id"));
                    m.put("name", bk.get("name"));
                    m.put("owner", bk.get("owner"));
                    m.put("scope", bk.get("scope"));
                    m.put("sharedWith", bk.get("sharedWith"));
                    m.put("version", bk.get("version"));
                    out.put("basket", m);
                });
            }
        }
        out.put("children", parts.size());
        out.put("childKind", parts.isEmpty() ? null : parts.get(0).kind());
        out.put("sumStandalone", parts.isEmpty() ? null : round(standalone));
        out.put("diversification", parts.size() < 2 ? null : round(standalone - s.measures().var()));
        double limit = limitOf(s);
        out.put("limit", Double.isNaN(limit) ? null : round(limit));
        out.put("limitUse", Double.isNaN(limit) || limit == 0 ? null : s.measures().var() / limit);
        Map<String, Object> hist = history(s);
        out.put("backtest", hist.get("backtest"));
        out.put("ancestors", ancestors(b, s.res().node(), s));
        out.put("provenance", provenance(s));
        svc.audit(p, "var-view", s, null);
        return out;
    }

    private static double limitOf(VarService.Scoped s) {
        if (s.res().node() != null) {
            return s.date().book().node(s.res().node()).limit();
        }
        if ("portfolio".equals(s.kind())) {
            String id = s.res().canonical().substring("PORT:".length());
            VarDate.Portfolio pf = s.date().portfolios().get(id);
            return pf == null ? Double.NaN : pf.limit();
        }
        return Double.NaN;
    }

    /**
     * A book's node in the primary hierarchy: {@code MGMT~<book>}, else the first leaf whose only book it is (a hierarchy whose ids are not
     * the books' own); null when none.
     */
    static String bookNode(VarBook b, String book) {
        if (book == null) {
            return null;
        }
        if (b.node("MGMT~" + book) != null) {
            return "MGMT~" + book;
        }
        for (VarBook.Node n : b.nodes().values()) {
            if (n.children().isEmpty() && n.books().equals(List.of(book))) {
                return n.id();
            }
        }
        return null;
    }

    /** The path from the root to the node, for the breadcrumb (positions and trades: their book's path, then the position). */
    private static List<Map<String, Object>> ancestors(VarBook b, String node, VarService.Scoped s) {
        List<Map<String, Object>> out = new ArrayList<>();
        String n = node;
        if (n == null && (s.res().position() >= 0 || s.res().trade() >= 0)) {
            int o = s.res().trade() >= 0 ? s.res().trade() : b.positionFrom(s.res().position());
            n = bookNode(b, b.trades().text("TT_BOOK", o));
            if (s.res().trade() >= 0) {
                int pi = b.positionOf(o);
                out.add(crumb(b.positionId(pi), "POS:" + b.positionId(pi)));
            }
        }
        while (n != null && b.node(n) != null) {
            VarBook.Node nn = b.node(n);
            out.add(0, crumb(nn.name(), "NODE:" + nn.id()));
            n = nn.parent();
        }
        return out;
    }

    private static Map<String, Object> crumb(String label, String scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("scope", scope);
        return m;
    }

    /** How the number was built: the members, the data, the method, the engine, the viewer's view, the time (from the result's stamp). */
    private Map<String, Object> provenance(VarService.Scoped s) {
        VarBook b = s.date().book();
        VarProperties pr = svc.props();
        int positions = 0;
        int[] last = {-1};
        int[] count = {0};
        s.set().forEach(o -> {
            int pi = b.positionOf(o);
            if (pi != last[0]) {
                last[0] = pi;
                count[0]++;
            }
        });
        positions = count[0];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", s.res().canonical());
        m.put("currency", s.date().currency());
        m.put("intraday", intradayNote(s.date()));
        m.put("intradayRejections", s.date().intraday() == null ? List.of() : s.date().intraday().rejections());
        m.put("scenarioWindow", scenarioWindow(s.date()));
        m.put("confidence", svc.props().confidence());
        m.put("esConfidence", svc.props().esConfidence());
        // per source system on the date: the form and grain of its records, how many, how decided, the P&L rows (VAR_GUIDE.md 15.13)
        m.put("inputsBySystem", s.date().intake().inputs() == null ? Map.of() : s.date().intake().inputs());
        m.put("fx", fxNote(s.date()));
        m.put("resolved", s.res().notes());
        m.put("trades", s.set().size());
        m.put("positions", positions);
        m.put("failed", b.failed());
        m.put("hidden", s.hidden());
        m.put("unknown", s.res().unknown());
        m.put("notices", selection.notices(s));
        int fromPv = s.date().intake().scenarioPv();
        m.put("vectors", svc.props().kinds().vector() + (fromPv == 0 ? "" : " and " + svc.props().kinds().scenarioPv() + " (" + fromPv
                + " trades from scenario PVs)") + " · batch " + String.join(", ", s.date().intake().batchIds()) + " of " + s.date().date()
                + " · data version " + s.date().version());
        m.put("inputs", s.date().inputs());
        m.put("rejected", s.date().intake().rejected());
        m.put("rejections", s.date().intake().rejections());
        m.put("scenarioPvTrades", s.date().intake().scenarioPv());
        m.put("excluded", s.date().intake().excluded());
        m.put("positionRules", s.date().intake().rules());
        List<String> warnings = new ArrayList<>(s.date().warnings());
        warnings.addAll(svc.loader().hierarchyNotes(s.date()));        // 2.9.5: split positions, limits naming no node
        warnings.addAll(s.res().warnings());
        m.put("warnings", warnings);
        m.put("scenarioSet", b.scenarioSet() + " · " + b.scenarioDates().get(0) + " .. " + b.scenarioDates().get(b.width() - 1));
        m.put("method", "historical, equal weights, 1-day, " + (pr.rule() == VarMath.RankRule.KTH_WORST ? "k = " + ordinal(s.measures().k())
                + " worst of " + b.width() : "interpolated between the order statistics"));
        m.put("es", Math.round(pr.esConfidence() * 1000) / 10.0 + "%, mean of worst " + s.measures().esCount());
        m.put("components", "Euler on the " + pr.componentWindow() + " scenarios around the VaR rank" + (pr.componentScaleToVar()
                ? ", scaled to add up to the VaR" : ""));
        m.put("engine", "VaR engine in the server · " + ENGINE + " · " + svc.sums().threads() + " threads");
        m.put("view", s.view().full() ? "every node at positions level" : "restricted by node (" + s.hidden() + " trades outside your entitlement)");
        m.put("tier", VarDate.USD.equals(s.date().currency()) ? tier(s)
                : "per-trade-currency totals of the USD book (float64), converted with the scenario FX of " + s.date().currency());
        int all = s.date().book().tradeCount();
        m.put("input", fromPv == 0 ? "P&L vectors (USD)" : fromPv >= all ? "scenario PVs in each trade's currency"
                : "P&L vectors (USD) and scenario PVs in each trade's currency (" + fromPv + " of " + all + " trades)");
        m.put("membershipHash", s.set().digest());
        m.put("computedAt", Instant.now().toString());
        m.put("computeMs", s.computeMs());
        m.put("cache", s.cached() ? "hit" : "miss");
        m.put("loadedMs", s.date().loadMs());
        if (s.shards() != null) {
            m.put("shards", s.shards());                              // a sharded date (VAR_SHARDING.md): the shards asked, who answered
        }
        return m;
    }

    /**
     * The intraday label (VAR_GUIDE.md section 8.4): what the figures include beyond the morning run, or null when nothing: the updates
     * applied, the trades changed (amended, added, cancelled), the newest {@code COMPUTED_AT}, the batches; the official records never
     * include them.
     */
    static Map<String, Object> intradayNote(VarDate d) {
        com.ash.drishti.compute.var.VarIntraday l = d.book().intraday();
        if (l == null) {
            return null;
        }
        com.ash.drishti.compute.var.VarIntraday.Info i = l.info();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("updates", i.updates());
        m.put("trades", i.trades());
        m.put("amended", i.amended());
        m.put("added", i.added());
        m.put("cancelled", i.cancelled());
        m.put("lastAt", i.lastAt());
        m.put("batches", i.batches());
        m.put("batchCount", i.batchCount());
        if (d.intraday() != null) {
            m.put("olderIgnored", d.intraday().older());
            m.put("beforeMorningIgnored", d.intraday().beforeMorning());
            m.put("morningAt", d.intraday().morningAt());
            m.put("rejections", d.intraday().rejections());
        }
        m.put("note", "intraday: the morning run with " + i.trades() + " trade" + (i.trades() == 1 ? "" : "s") + " updated since (latest "
                + i.lastAt() + "); the official end-of-day records are the morning run's");
        return m;
    }

    /**
     * How the date's vectors met FX (VAR_GUIDE.md sections 15.9 and 15.10): the reporting currency, and per currency converted the
     * source of its scenario rates (supplied by the batch, or derived from the FX history) and today's rate in USD per unit; every
     * cross goes through USD.
     */
    static Map<String, Object> fxNote(VarDate d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reporting", d.currency());
        m.put("crosses", "through USD");
        m.put("rates", d.fx());
        java.util.TreeSet<String> sources = new java.util.TreeSet<>();
        d.fx().values().forEach(v -> {
            if (v instanceof Map<?, ?> r && r.get("source") != null && !"base".equals(r.get("source"))) {   // USD itself: no rates
                sources.add(String.valueOf(r.get("source")));
            }
        });
        m.put("scenarioFx", sources.isEmpty() ? "none needed (every trade in USD)" : String.join(" and ", sources));
        return m;
    }

    /** Where the answer's vector came from (VAR_GUIDE.md section 26): a node's float64 aggregate, the hot file's float32 rows, or the heap. */
    static String tier(VarService.Scoped s) {
        VarBook b = s.date().book();
        boolean node = s.res().node() != null && b.node(s.res().node()) != null && s.set().equals(b.node(s.res().node()).members());
        if (node) {
            return "node aggregate (float64)";
        }
        return "mapped".equals(b.tier()) ? "hot file (float32 rows, summed in float64)" : "memory (float64 rows)";
    }

    private static String ordinal(int k) {
        return k + (k % 100 >= 11 && k % 100 <= 13 ? "th" : switch (k % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        });
    }

    // ------------------------------------------------------------------------------------------------------------------- parts

    /** A part of a scope with its vector, the scope text that opens it, and its attributes for the tables. */
    record Part(String key, String scope, String label, String kind, double[] vector, double size, Map<String, Object> attrs) {}

    /** The parts computed, and how many there were before {@code max-parts} kept the largest. */
    record Parts(List<Part> list, int total) {
        boolean truncated() {
            return total > list.size();
        }
    }

    List<Part> parts(VarService.Scoped s, String by, Principal p) {
        return partsOf(s, by, p).list();
    }

    /**
     * The scope's parts: {@code child} (the default: a node's child nodes, a book's positions, a position's trades, an ad-hoc scope's
     * positions), {@code node} (a node's child nodes), {@code position}, {@code trade}, {@code desk} or {@code book}. Positions and trades
     * only for a viewer who sees them. More positions or trades than {@code max-parts}: the largest by gross size (market value, else
     * PV, else quantity) are kept, and the answer says so.
     */
    Parts partsOf(VarService.Scoped s, String by, Principal p) {
        VarBook b = s.date().book();
        String mode = by == null || by.isBlank() ? "child" : by.trim().toLowerCase(java.util.Locale.ROOT);
        if (mode.equals("node") && (s.res().node() == null || b.node(s.res().node()).children().isEmpty())) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "by=node lists a node's child nodes; " + s.res().canonical()
                    + (s.res().node() == null ? " is not a node" : " has none") + ": use by=position, trade, desk or book");
        }
        if (mode.equals("child")) {
            if (s.res().node() != null) {
                VarBook.Node n = b.node(s.res().node());
                mode = n.children().isEmpty() ? "position" : "node";
            } else if (s.res().position() >= 0) {
                mode = "trade";
            } else if (s.res().trade() >= 0) {
                return new Parts(List.of(), 0);
            } else {
                mode = selection.byTerms(p, s) ? "part" : "position";
            }
        }
        if (mode.equals("part")) {
            List<Part> terms = selection.parts(p, s);
            return new Parts(terms, terms.size());
        }
        if ((mode.equals("position") || mode.equals("trade")) && !s.view().seesPositions(s.set())) {
            if (by == null || by.isBlank() || by.equals("child")) {
                return new Parts(List.of(), 0);                          // an aggregate-only viewer: no position level below this node
            }
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role sees this scope in aggregate only: no positions or trades");
        }
        List<Part> out = new ArrayList<>();
        int max = svc.props().maxParts();
        int[] total = {0};
        switch (mode) {
            case "node" -> {
                for (String c : b.node(s.res().node()).children()) {
                    VarBook.Node cn = b.node(c);
                    if (cn == null || !VarAccess.seesNode(s.view(), cn)) {
                        continue;
                    }
                    OrdinalSet m = cn.members().and(s.set());
                    double[] v = m.equals(cn.members()) ? b.aggregate(cn.id()) : b.vector(m, svc.sums());
                    out.add(new Part(cn.id(), "NODE:" + cn.id(), cn.name(), cn.level(), v, Double.NaN, Map.of("level", cn.level())));
                }
            }
            case "desk", "book" -> {
                String group = mode;
                String col = group.equals("desk") ? "DESK" : "TT_BOOK";
                Map<String, OrdinalSet.Builder> groups = new LinkedHashMap<>();
                s.set().forEach(o -> groups.computeIfAbsent(String.valueOf(b.trades().text(col, o)), k -> new OrdinalSet.Builder()).add(o));
                groups.forEach((k, g) -> {
                    OrdinalSet m = g.build();
                    String node = group.equals("book") ? String.valueOf(bookNode(b, k)) : "MGMT~" + k;
                    boolean whole = b.node(node) != null && m.equals(b.node(node).members());
                    out.add(new Part(k, b.node(node) != null ? "NODE:" + node : col.equals("DESK") ? "DESK:" + k : "BOOK:" + k, k, group,
                            whole ? b.aggregate(node) : b.vector(m, svc.sums()), Double.NaN, Map.of()));
                });
            }
            case "position" -> {
                int[] last = {-1};
                List<int[]> runs = new ArrayList<>();
                s.set().forEach(o -> {
                    int pi = b.positionOf(o);
                    if (pi != last[0]) {
                        runs.add(new int[] {pi, o, o + 1});
                        last[0] = pi;
                    } else {
                        runs.get(runs.size() - 1)[2] = o + 1;
                    }
                });
                total[0] = runs.size();
                for (int[] r : largest(runs, max, r -> positionSize(b, s.set(), r[0]))) {
                    out.add(position(b, s.set(), r[0]));
                }
            }
            case "trade" -> {
                List<Integer> os = new ArrayList<>();
                s.set().forEach(os::add);
                total[0] = os.size();
                for (int o : largest(os, max, o -> tradeSize(b, o))) {
                    out.add(trade(b, o));
                }
            }
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "by is child, node, part, position, trade, desk or book");
        }
        return new Parts(out, Math.max(total[0], out.size()));
    }

    /** At most {@code max} of the items, the largest by {@code size} (ties: the first), kept in their order. */
    private static <T> List<T> largest(List<T> items, int max, java.util.function.ToDoubleFunction<T> size) {
        if (items.size() <= max) {
            return items;
        }
        Integer[] idx = new Integer[items.size()];
        double[] sz = new double[items.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
            sz[i] = size.applyAsDouble(items.get(i));
        }
        java.util.Arrays.sort(idx, (a, c) -> sz[a] != sz[c] ? Double.compare(sz[c], sz[a]) : Integer.compare(a, c));
        Integer[] keep = java.util.Arrays.copyOf(idx, max);
        java.util.Arrays.sort(keep);
        List<T> out = new ArrayList<>(max);
        for (Integer i : keep) {
            out.add(items.get(i));
        }
        return out;
    }

    /** A position's gross size: its market value, else its trades' PV, else their quantities (absolute values). */
    private static double positionSize(VarBook b, OrdinalSet set, int pi) {
        double mv = b.positions().number("MARKET_VALUE", pi);
        OrdinalSet members = b.positionMembers(pi);                  // its run, and any trade added during the day
        if (!Double.isNaN(mv) && set.containsAll(members)) {
            return Math.abs(mv);
        }
        double[] s = {0};
        members.and(set).forEach(o -> s[0] += tradeSize(b, o));
        return s[0];
    }

    private static double tradeSize(VarBook b, int o) {
        double pv = b.trades().number("PV", o);
        return Double.isNaN(pv) ? Math.abs(nz(b.trades().number("QUANTITY", o))) : Math.abs(pv);
    }

    private Part position(VarBook b, OrdinalSet set, int pi) {
        OrdinalSet members = b.positionMembers(pi);
        boolean whole = set.containsAll(members);
        double[] v;
        double qty;
        Columns pc = b.positions();
        if (whole) {
            v = b.positionVector(pi);
            qty = pc.number("QUANTITY", pi);
        } else {                                                     // part of a position: the trades of it the scope holds
            double[] acc = new double[b.width()];
            double[] q = {0};
            members.and(set).forEach(o -> {
                b.store().addInto(acc, o);
                q[0] += nz(b.trades().number("QUANTITY", o));
            });
            v = acc;
            qty = q[0];
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("instrument", pc.text("INSTRUMENT", pi));
        a.put("direction", qty > 0 ? "LONG" : qty < 0 ? "SHORT" : "FLAT");
        a.put("quantity", qty);
        a.put("marketValue", nan(pc.number("MARKET_VALUE", pi)));
        a.put("currency", pc.text("CURRENCY", pi));
        a.put("tradeCurrency", positionCurrency(b, set, pi));
        a.put("book", pc.text("TT_BOOK", pi));
        a.put("productType", pc.text("PRODUCT_TYPE", pi));
        a.put("securityId", pc.text("SECURITY_ID", pi));
        a.put("rule", pc.text("POSITION_RULE", pi));
        a.put("trades", whole ? members.size() : members.and(set).size());
        a.put("partial", !whole);
        String id = b.positionId(pi);
        return new Part(id, "POS:" + id, pc.text("INSTRUMENT", pi) == null ? id : pc.text("INSTRUMENT", pi), "position", v,
                Double.isNaN(pc.number("MARKET_VALUE", pi)) ? Double.NaN : pc.number("MARKET_VALUE", pi), a);
    }

    /**
     * A trade's currency (VAR_GUIDE.md section 15.11): the currency its PVs came in (a scenario-PV row's {@code CURRENCY}), else the
     * trade's own {@code CURRENCY} (a P&amp;L vector, sent in USD); {@code [currency, where from]}.
     */
    static String[] tradeCurrency(Columns tc, int o) {
        String pv = tc.has(CurrencyTotals.COLUMN) ? tc.text(CurrencyTotals.COLUMN, o) : null;
        if (pv != null) {
            return new String[] {pv, "scenario PVs"};
        }
        String own = tc.text("CURRENCY", o);
        return new String[] {own, own == null ? null : "trade"};
    }

    /** A position's trade currency: its trades' when they agree, else {@code mixed}. */
    private static String positionCurrency(VarBook b, OrdinalSet set, int pi) {
        java.util.Set<String> seen = new java.util.TreeSet<>();
        b.positionMembers(pi).and(set).forEach(o -> {
            String t = tradeCurrency(b.trades(), o)[0];
            if (t != null) {
                seen.add(t);
            }
        });
        return seen.isEmpty() ? null : seen.size() > 1 ? "mixed" : seen.iterator().next();
    }

    private static Part trade(VarBook b, int o) {
        Columns tc = b.trades();
        double q = nz(tc.number("QUANTITY", o));
        Map<String, Object> a = new LinkedHashMap<>();
        String[] ccy = tradeCurrency(tc, o);
        a.put("tradeCurrency", ccy[0]);
        a.put("tradeCurrencyFrom", ccy[1]);
        a.put("direction", q > 0 ? "LONG" : q < 0 ? "SHORT" : "FLAT");
        a.put("quantity", q);
        a.put("productType", tc.text("PRODUCT_TYPE", o));
        a.put("counterparty", tc.text("COUNTERPARTY", o));
        a.put("position", tc.text("POSITION_ID", o));
        a.put("book", tc.text("TT_BOOK", o));
        a.put("pv", nan(tc.number("PV", o)));
        String id = b.tradeId(o);
        if (b.supplied(o)) {                                            // a supplied position (position grain): listed as the position it is
            a.put("note", "supplied whole by its source system: a position, no trades of its own");
            return new Part(id, "POS:" + id, id, "position", b.tradeVector(o), nz(tc.number("PV", o)) == 0 ? Double.NaN : tc.number("PV", o), a);
        }
        return new Part(id, "TRD:" + id, id, "trade", b.tradeVector(o), nz(tc.number("PV", o)) == 0 ? Double.NaN : tc.number("PV", o), a);
    }

    // ------------------------------------------------------------------------------------------------------------------- contributions

    Map<String, Object> contributions(Principal p, String scope, LocalDate date, String by, String scenario) {
        return contributions(p, scope, date, by, scenario, VarDate.USD);
    }

    Map<String, Object> contributions(Principal p, String scope, LocalDate date, String by, String scenario, String ccy) {
        VarService.Scoped s = svc.scoped(p, scope, date, ccy);
        VarBook b = s.date().book();
        Parts ps = partsOf(s, by, p);
        List<Part> parts = ps.list();
        List<VarCalc.Part> cp = parts.stream().map(x -> new VarCalc.Part(x.key(), x.label(), x.kind(), x.vector(), x.size())).toList();
        List<VarCalc.Contribution> cs = svc.calc().contributions(s.vector(), s.measures(), cp);
        int scen = scenario == null || scenario.isBlank() ? -1 : b.scenarioDates().indexOf(LocalDate.parse(scenario));
        if (scenario != null && !scenario.isBlank() && scen < 0) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, scenario + " is not a scenario date of " + b.scenarioSet());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        double standalone = 0;
        double comp = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part pt = parts.get(i);
            VarCalc.Contribution c = cs.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", pt.key());
            r.put("scope", pt.scope());
            r.put("label", pt.label());
            r.put("kind", pt.kind());
            r.put("standaloneVar", round(pt.attrs().get("alone") instanceof Number n ? n.doubleValue() : c.standaloneVar()));
            r.put("standaloneEs", round(c.standaloneEs()));
            r.put("componentVar", round(c.componentVar()));
            r.put("componentEs", round(c.componentEs()));
            r.put("pct", c.pct());
            r.put("incrementalVar", round(c.incrementalVar()));
            r.put("marginalPer1m", Double.isNaN(c.marginalPer1m()) ? null : round(c.marginalPer1m()));
            if (scen >= 0) {
                r.put("scenarioPnl", round(pt.vector()[scen]));
            }
            pt.attrs().forEach((k, v) -> r.put(k, v != null && svc.access().masked(p, k) ? com.ash.drishti.api.DataNode.MASK : v));
            if (pt.kind().equals("node") || !pt.attrs().containsKey("level")) {
                r.put("level", pt.attrs().getOrDefault("level", pt.kind()));
            }
            standalone += pt.attrs().get("alone") instanceof Number n ? n.doubleValue() : c.standaloneVar();
            comp += c.componentVar();
            rows.add(r);
        }
        if (scen >= 0) {
            rows.sort(Comparator.comparingDouble(r -> ((Number) r.get("scenarioPnl")).doubleValue()));
        } else {
            rows.sort(Comparator.comparingDouble(r -> -((Number) r.get("componentVar")).doubleValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", s.res().canonical());
        out.put("currency", s.date().currency());
        out.put("intraday", intradayNote(s.date()));
        out.put("by", parts.isEmpty() ? null : parts.get(0).kind());
        out.put("var", round(s.measures().var()));
        out.put("es", round(s.measures().es()));
        out.put("sumStandalone", round(standalone));
        out.put("sumComponent", round(comp));
        out.put("diversification", round(standalone - s.measures().var()));
        out.put("truncated", ps.truncated());
        out.put("total", ps.total());
        if (scen >= 0) {
            out.put("scenario", scenario);
            out.put("scenarioPnl", round(s.vector()[scen]));
            out.put("scenarioRank", rankOf(s.measures().order(), scen));
        }
        out.put("rows", rows);
        out.put("computeMs", s.computeMs());
        return out;
    }

    private static int rankOf(int[] order, int scen) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == scen) {
                return i + 1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------------------------- currencies

    /**
     * The scope in every listed reporting currency (VAR_GUIDE.md section 15.11, the explorer's "In other currencies" tab): each currency's
     * VaR and ES computed from the scope's per-trade-currency totals with that currency's scenario FX (never the page's VaR at a rate),
     * that VaR in the page's currency {@code ccy} at today's rate, and the difference from the page's VaR. As the viewer.
     */
    Map<String, Object> inCurrencies(Principal p, String scope, LocalDate date, String ccy) {
        long t0 = System.nanoTime();
        String page = VarCurrencies.normalise(ccy);
        VarService.Scoped s = svc.scoped(p, scope, date);
        VarDate usd = s.date();
        ScenarioFx fx = svc.fx(usd);
        if (fx.rates(page).isEmpty()) {
            throw new DrishtiException(ErrorCode.VAR_NO_FX, "no " + page + " FX for the scenarios of " + usd.date() + "; the currencies with FX on "
                    + "the date: " + String.join(", ", fx.currencies()));
        }
        CurrencyTotals.Sums totals = svc.totals(usd, s.set(), s.res().node());
        List<String> list = new ArrayList<>(svc.props().currencies());
        if (!list.contains(page)) {
            list.add(page);
        }
        Map<String, VarCalc.Measures> measures = new LinkedHashMap<>();
        for (String c : list) {
            double[] v = c.equals(VarDate.USD) ? s.vector() : totals.in(fx, c);
            measures.put(c, v == null ? null : svc.calc().measures(v));
        }
        double pageVar = measures.get(page) == null ? Double.NaN : measures.get(page).var();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String c : list) {
            VarCalc.Measures m = measures.get(c);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("currency", c);
            r.put("shown", c.equals(page));
            r.put("listed", svc.props().currencies().contains(c));
            if (m == null) {
                r.put("note", "no " + c + " scenario FX on " + usd.date());
                rows.add(r);
                continue;
            }
            double rate = fx.cross(c, page, -1);
            double inPage = m.var() * rate;
            r.put("var", round(m.var()));
            r.put("es", round(m.es()));
            r.put("rate", rate);
            r.put("varInPage", round(inPage));
            r.put("difference", c.equals(page) ? null : round(inPage - pageVar));
            r.put("differencePct", c.equals(page) || pageVar == 0 ? null : (inPage - pageVar) / pageVar);
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", s.res().canonical());
        out.put("label", s.label());
        out.put("businessDate", usd.date().toString());
        out.put("currency", page);
        out.put("var", round(pageVar));
        out.put("rows", rows);
        out.put("tradeCurrencies", java.util.Arrays.asList(totals.currencies()));
        out.put("note", "each currency's VaR is computed from the scope's PVs summed per trade currency, converted with that currency's "
                + "scenario FX (crosses through USD): the currency move on the scenario days is part of its risk, so it is not the " + page
                + " VaR at today's rate");
        out.put("computeMs", (System.nanoTime() - t0) / 1_000_000);
        svc.audit(p, "var-currencies", s, "page=" + page);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------------------- path

    Map<String, Object> path(Principal p, String scope, LocalDate date, int page, int size) {
        return path(p, scope, date, page, size, VarDate.USD);
    }

    Map<String, Object> path(Principal p, String scope, LocalDate date, int page, int size, String ccy) {
        VarService.Scoped s = svc.scoped(p, scope, date, ccy);
        VarBook b = s.date().book();
        double[] v = s.vector();
        int[] order = s.measures().order();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonical", s.res().canonical());
        out.put("currency", s.date().currency());
        out.put("intraday", intradayNote(s.date()));
        out.put("var", round(s.measures().var()));
        out.put("es", round(s.measures().es()));
        out.put("k", s.measures().k());
        out.put("esCount", s.measures().esCount());
        VarCalc.Histogram h = VarCalc.histogram(v, svc.props().histogramBins());
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("edges", roundAll(h.edges()));
        hist.put("counts", h.counts());
        out.put("histogram", hist);
        List<Map<String, Object>> worst = new ArrayList<>();
        for (int r = 0; r < Math.min(25, order.length); r++) {
            worst.add(scenarioRow(b, order[r], r + 1, v[order[r]]));
        }
        out.put("worst", worst);
        long[] series = new long[v.length];
        for (int j = 0; j < v.length; j++) {
            series[j] = Math.round(v[j]);
        }
        out.put("series", series);
        out.put("dates", List.of(b.scenarioDates().get(0).toString(), b.scenarioDates().get(b.width() - 1).toString()));
        out.put("scenarioDates", b.scenarioDates().stream().map(LocalDate::toString).toList());     // about 6 KB: the series chart's axis
        // the ranked table, a page at a time, with the part that lost most on each scenario
        List<Part> parts = parts(s, "child", p);
        int sz = size <= 0 ? svc.props().pathPage() : Math.min(size, 509);
        int from = Math.max(0, page) * sz;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int r = from; r < Math.min(order.length, from + sz); r++) {
            int j = order[r];
            Map<String, Object> row = scenarioRow(b, j, r + 1, v[j]);
            Part worstPart = null;
            for (Part pt : parts) {
                if (worstPart == null || pt.vector()[j] < worstPart.vector()[j]) {
                    worstPart = pt;
                }
            }
            if (worstPart != null) {
                if (worstPart.attrs().get("tradeCurrency") != null) {
                    row.put("fromCurrency", worstPart.attrs().get("tradeCurrency"));
                }
                row.put("from", worstPart.label());
                row.put("fromScope", worstPart.scope());
                row.put("fromPnl", round(worstPart.vector()[j]));
            }
            rows.add(row);
        }
        out.put("page", Math.max(0, page));
        out.put("pageSize", sz);
        out.put("total", order.length);
        out.put("rows", rows);
        out.put("computeMs", s.computeMs());
        return out;
    }

    private static Map<String, Object> scenarioRow(VarBook b, int j, int rank, double pnl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rank", rank);
        m.put("index", j);
        m.put("date", b.scenarioDates().get(j).toString());
        m.put("pnl", round(pnl));
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------------- history

    Map<String, Object> history(Principal p, String scope, LocalDate date) {
        return history(svc.scoped(p, scope, date));
    }

    Map<String, Object> history(Principal p, String scope, LocalDate date, String ccy) {
        return history(svc.scoped(p, scope, date, ccy));
    }

    /**
     * A node's official history (its official end-of-day captures: VaR, ES and the day's hypothetical P&amp;L over the window) and its
     * backtest; any other scope replays today's members over the window's scenario days against today's VaR (in-sample: it says so; not
     * a regulatory backtest). The Basel light of a history shorter than 250 days scales its limits (VAR_GUIDE.md section 18.4): it is shown with
     * its days, and is not a regulatory zone before 250.
     */
    Map<String, Object> history(VarService.Scoped s) {
        VarBook b = s.date().book();
        double conf = svc.props().confidence();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        VarOfficialStore.NodeHistory h = s.res().node() != null && s.view().full()
                ? svc.official().history(s.res().node(), s.date().date(), svc.props().historyDays(), s.date().currency()) : null;
        out.put("currency", s.date().currency());
        Backtest bt;
        if (h != null && h.dates().length > 1) {
            bt = Backtest.of(h.hypo(), h.var(), conf);
            for (int i = 0; i < h.dates().length; i++) {
                rows.add(historyRow(h.dates()[i], h.var()[i], h.es()[i], h.hypo()[i], bt.exceptionFlags()[i]));
            }
            out.put("mode", "official");
            out.put("note", "the node's official end-of-day captures, one a business day (" + h.dates().length + " days)");
        } else {
            int n = Math.min(svc.props().historyDays(), b.width());
            double[] pnl = new double[n];
            double[] var = new double[n];
            for (int i = 0; i < n; i++) {
                pnl[i] = s.vector()[b.width() - n + i];
                var[i] = s.measures().var();
            }
            bt = Backtest.of(pnl, var, conf);
            for (int i = 0; i < n; i++) {
                rows.add(historyRow(b.scenarioDates().get(b.width() - n + i), var[i], s.measures().es(), pnl[i], bt.exceptionFlags()[i]));
            }
            out.put("mode", "replay");
            out.put("note", "today's members over the last " + n + " scenario days against today's VaR: in-sample, not a regulatory backtest"
                    + (s.res().node() != null && !s.view().full() ? " (the official history covers trades outside your entitlement)" : ""));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", bt.days());
        m.put("exceptions", bt.exceptions());
        m.put("light", bt.light());
        m.put("kupiecLr", bt.kupiecLr());
        m.put("kupiecPValue", bt.kupiecPValue());
        m.put("christoffersenLr", bt.christoffersenLr());
        m.put("christoffersenPValue", bt.christoffersenPValue());
        out.put("backtest", m);
        out.put("rows", rows);
        return out;
    }

    private static Map<String, Object> historyRow(LocalDate d, double var, double es, double pnl, boolean exception) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.toString());
        m.put("var", round(var));
        m.put("es", round(es));
        m.put("pnl", round(pnl));
        m.put("exception", exception);
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------------- tree

    /** Every node of a hierarchy the viewer sees, with its VaR, ES, limit and counts (the explorer's left pane). */
    Map<String, Object> tree(Principal p, String hierarchy, LocalDate date) {
        return tree(p, hierarchy, date, VarDate.USD);
    }

    Map<String, Object> tree(Principal p, String hierarchy, LocalDate date, String ccy) {
        VarDate d = svc.date(date, ccy);
        VarBook b = d.book();
        List<String> present = b.hierarchies();
        String h = hierarchy == null || hierarchy.isBlank() ? (present.contains("MGMT") || present.isEmpty() ? "MGMT" : present.get(0))
                : hierarchy.toUpperCase(java.util.Locale.ROOT);
        VarBook.Node root = b.root(h);
        Map<String, String> skipped = svc.loader().skipped(d.date());
        if (root == null) {
            throw new DrishtiException(ErrorCode.VAR_HIERARCHY_UNKNOWN, skipped.containsKey(h) ? skipped.get(h)
                    : "no hierarchy " + h + " on " + d.date() + " (it has " + String.join(", ", present) + ")");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        walk(p, b, root, 0, rows, date, d.currency());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hierarchy", h);
        out.put("currency", d.currency());
        out.put("intraday", intradayNote(d));
        // the date's hierarchies (2.9.5): their ids as before, in the configured order, and each with its title, word and levels
        List<Map<String, Object>> info = svc.hierarchies().describe(present, skipped);
        out.put("hierarchies", info.stream().filter(m -> !m.containsKey("skipped")).map(m -> String.valueOf(m.get("id"))).toList());
        out.put("hierarchyInfo", info);
        out.put("title", svc.hierarchies().title(h));
        out.put("businessDate", d.date().toString());
        out.put("nodes", rows);
        List<Map<String, Object>> ports = new ArrayList<>();
        d.portfolios().values().forEach(pf -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pf.id());
            m.put("scope", "PORT:" + pf.id());
            m.put("name", pf.name());
            m.put("type", pf.type());
            m.put("owner", pf.owner());
            ports.add(m);
        });
        out.put("portfolios", ports);
        return out;
    }

    private void walk(Principal p, VarBook b, VarBook.Node n, int depth, List<Map<String, Object>> rows, LocalDate date, String ccy) {
        VarService.Scoped s;
        try {
            s = svc.scoped(p, "NODE:" + n.id(), date, ccy);
        } catch (DrishtiException e) {
            return;
        }
        if (s.set().isEmpty() && !s.view().full()) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("scope", "NODE:" + n.id());
        m.put("name", n.name());
        m.put("level", n.level());
        m.put("parent", n.parent());
        m.put("depth", depth);
        m.put("var", round(s.measures().var()));
        m.put("es", round(s.measures().es()));
        m.put("limit", Double.isNaN(n.limit()) ? null : round(n.limit()));
        m.put("limitUse", Double.isNaN(n.limit()) || n.limit() == 0 ? null : s.measures().var() / n.limit());
        m.put("trades", s.set().size());
        m.put("hidden", s.hidden());
        m.put("expandable", !n.children().isEmpty() || s.view().seesPositions(s.set()));
        rows.add(m);
        for (String c : n.children()) {
            VarBook.Node cn = b.node(c);
            if (cn != null) {
                walk(p, b, cn, depth + 1, rows, date, ccy);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- helpers

    static long round(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.round(v);
    }

    private static long[] roundAll(double[] v) {
        long[] out = new long[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = Math.round(v[i]);
        }
        return out;
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static Object nan(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
