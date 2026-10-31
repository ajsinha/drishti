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
import com.ash.drishti.compute.var.ScopeExpr;
import com.ash.drishti.compute.var.ScopeResolver;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Construct your basket" (VAR_GUIDE.md section 3.5): what the basket tray asks of the engine beside the headline.
 *
 * <ul>
 *   <li><b>Terms</b>: a scope's top-level parts with their sign ({@code PORT:A + CPTY:B - TRD:C}: +A, +B, -C); a saved basket's are
 *       those of its definition, so opening a basket loads its parts back into the tray;</li>
 *   <li><b>Parts</b> ({@code by=part}): each positive term inside the scope, with its own standalone VaR (the term alone, inside the
 *       scope); a trade in two terms is allocated to the first, so the components still add up to the scope's VaR;</li>
 *   <li><b>Notices</b>: an id that matches nothing, a trade that is not live on the date (its status, its maturity: no P&amp;L vector), a
 *       rule that matches no position, and an empty scope, said in words, so an empty scope never reads as a computed VaR of 0;</li>
 *   <li><b>Hints</b>: what a typed text may name (portfolios, saved baskets, desks, books, legal entities, divisions, counterparties,
 *       positions, trades), each with its type, id, name, trade count and standalone VaR as the viewer, for the tray's type-ahead.</li>
 * </ul>
 */
final class VarSelection {

    /** A top-level term of a scope and its sign. */
    record Term(String op, ScopeExpr expr) {}

    /** One entry of the hint catalogue of a date: a type, the scope that opens it, its id and name, and the text it is found by. */
    record Entry(String type, String scope, String id, String name, String find) {}

    /** The order hints are offered in: the larger groupings first. */
    private static final List<String> TYPES = List.of("PORT", "BASKET", "DESK", "BOOK", "LE", "DIV", "CPTY", "POS", "TRD");
    private static final int HINTS = 8;
    private final VarService svc;
    private final Map<String, List<Entry>> catalogues = new java.util.concurrent.ConcurrentHashMap<>();

    VarSelection(VarService svc) {
        this.svc = svc;
    }

    // ------------------------------------------------------------------------------------------------------------------- terms

    /** The scope's top-level terms with their sign; a single saved basket stands for its definition's terms. */
    List<Term> terms(Principal p, String scope) {
        ScopeExpr e = expanded(p, ScopeExpr.parse(scope, svc.words().keySet()));
        List<Term> out = new ArrayList<>();
        flatten(e, "+", out);
        return out;
    }

    /** The definition of a scope that is one saved basket (the caller may read), else the scope itself. */
    ScopeExpr expanded(Principal p, ScopeExpr e) {
        if (p != null && e instanceof ScopeExpr.Ref r && r.word().equals("BASKET")) {
            Optional<String> def = svc.saved().basket(p, r.arg()).map(b -> String.valueOf(b.get("scope")));
            if (def.isPresent()) {
                try {
                    return ScopeExpr.parse(def.get(), svc.words().keySet());
                } catch (IllegalArgumentException x) {
                    return e;
                }
            }
        }
        return e;
    }

    private static void flatten(ScopeExpr e, String op, List<Term> out) {
        String flip = op.equals("+") ? "-" : "+";
        switch (e) {
            case ScopeExpr.Union u -> u.terms().forEach(t -> flatten(t, op, out));
            case ScopeExpr.Minus m -> {
                flatten(m.left(), op, out);
                if (m.right() instanceof ScopeExpr.Minus) {
                    out.add(new Term(flip, m.right()));               // A - (B - C): kept whole
                } else {
                    flatten(m.right(), flip, out);
                }
            }
            default -> out.add(new Term(op, e));
        }
    }

    /** The terms as the API answers them: {@code [{op, scope, word}]}. */
    static List<Map<String, Object>> describe(List<Term> terms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Term t : terms) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", t.op());
            m.put("scope", t.expr().toString());
            m.put("word", t.expr() instanceof ScopeExpr.Ref r ? r.word() : "SCOPE");
            out.add(m);
        }
        return out;
    }

    /** True when a scope's default parts are its terms: an ad-hoc scope or a basket with two terms or more to add. */
    boolean byTerms(Principal p, VarService.Scoped s) {
        if (!s.kind().equals("adhoc") && !s.kind().equals("basket")) {
            return false;
        }
        try {
            return terms(p, s.res().canonical()).stream().filter(t -> t.op().equals("+")).count() >= 2;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- parts

    /**
     * The scope's positive terms as its parts: each the term's trades inside the scope (so a term minus another is already taken out);
     * the vector of a part is its trades not in an earlier part (components add up), its standalone VaR that of the term alone.
     */
    List<VarViews.Part> parts(Principal p, VarService.Scoped s) {
        VarDate d = s.date();
        VarBook b = d.book();
        ScopeResolver resolver = new ScopeResolver(b, svc.definitions(p, d), svc.words());
        OrdinalSet taken = OrdinalSet.empty();
        List<VarViews.Part> out = new ArrayList<>();
        for (Term t : terms(p, s.res().canonical())) {
            if (!t.op().equals("+")) {
                continue;
            }
            String text = t.expr().toString();
            OrdinalSet full;
            try {
                full = resolver.resolve(text).set().and(s.set());
            } catch (IllegalArgumentException e) {
                full = OrdinalSet.empty();
            }
            OrdinalSet mine = full.andNot(taken);
            taken = taken.or(full);
            // 2.9.7: the term alone through the answer's lens (its horizon, method and confidences), from its own 1-day vector
            com.ash.drishti.compute.var.VarLens lens = s.lens() == null ? svc.props().defaultLens() : s.lens();
            double[] fullDay = b.vector(full, svc.sums());
            VarCalc.Measures alone = lens.measures(svc.calc(), fullDay, lens.apply(fullDay));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("term", text);
            a.put("word", t.expr() instanceof ScopeExpr.Ref r ? r.word() : "SCOPE");
            a.put("trades", full.size());
            a.put("allocated", mine.size());
            a.put("alone", VarViews.round(alone.var()));
            a.put("aloneEs", VarViews.round(alone.es()));
            out.add(new VarViews.Part(text, text, label(p, d, t.expr()), "part", lens.apply(b.vector(mine, svc.sums())), Double.NaN, a));
        }
        return out;
    }

    /** A term's name: a portfolio's or basket's title, a node's name, else the term. */
    String label(Principal p, VarDate d, ScopeExpr e) {
        if (!(e instanceof ScopeExpr.Ref r)) {
            return e.toString();
        }
        switch (r.word()) {
            case "PORT" -> {
                VarDate.Portfolio pf = d.portfolios().get(r.arg());
                return pf == null || pf.name() == null ? r.toString() : pf.name();
            }
            case "BASKET" -> {
                return p == null ? r.toString() : svc.saved().basket(p, r.arg()).map(m -> "★ " + m.get("name")).orElse(r.toString());
            }
            case "BANK", "DIV", "DESK", "BOOK", "NODE" -> {
                String id = new ScopeResolver(d.book(), svc.definitions(p, d)).nodeId(r);
                return id == null ? r.toString() : d.book().node(id).name();
            }
            case "WHERE" -> {
                return "rule: " + r.arg();
            }
            default -> {
                if (svc.words().containsKey(r.word())) {                    // a configured hierarchy's node (2.9.5)
                    String id = new ScopeResolver(d.book(), svc.definitions(p, d), svc.words()).nodeId(r);
                    return id == null ? r.toString() : d.book().node(id).name();
                }
                return r.toString();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------- notices

    /**
     * What the scope names but cannot count, in words: an id the date does not hold, a trade that is not live (with why: its status,
     * its maturity), a rule that matches no position; and an empty scope.
     */
    List<String> notices(VarService.Scoped s) {
        List<String> out = new ArrayList<>();
        String date = s.date().date().toString();
        List<String> unknown = s.res().unknown();
        ScopeExpr parsed;
        try {
            parsed = ScopeExpr.parse(s.res().canonical(), svc.words().keySet());
        } catch (IllegalArgumentException e) {
            return out;
        }
        java.util.Set<String> said = new java.util.HashSet<>();
        for (ScopeExpr.Ref r : parsed.refs()) {
            if (r.word().equals("TRD") || r.word().equals("POS")) {
                for (String id : r.ids()) {
                    if (unknown.contains(id) && said.add(r.word() + ":" + id)) {
                        out.add(r.word().equals("TRD") ? trade(s, id, date) : "POS:" + id + " matches no position on " + date
                                + " (no live trade of it has a P&L vector)");
                    }
                }
            } else if (unknown.contains(r.toString()) && said.add(r.toString())) {
                out.add(r.toString() + " matches nothing on " + date + (r.word().equals("BASKET") || r.word().equals("CAPTURE")
                        ? " (no such " + r.word().toLowerCase(Locale.ROOT) + " you may read)" : ""));
            }
        }
        for (String u : unknown) {                                        // named inside a basket or a portfolio
            if (!said.contains("TRD:" + u) && !said.contains("POS:" + u) && !said.contains(u)) {
                said.add(u);
                String why = s.date().intake().notLiveWhy(u);
                out.add(why != null ? u + " is not live on " + date + ": " + why + " (no P&L vector)" : u + " matches nothing on " + date);
            }
        }
        for (String n : s.res().notes()) {
            if (n.equals("rule matched 0 positions")) {
                out.add("a rule matches no position on " + date);
            }
        }
        if (s.set().isEmpty()) {
            out.add(s.hidden() > 0 ? "every trade of this scope is outside your view: nothing to compute"
                    : "no trades: this scope holds no live trade with a P&L vector on " + date + ", so it has no VaR");
        }
        return out;
    }

    private static String trade(VarService.Scoped s, String id, String date) {
        String why = s.date().intake().notLiveWhy(id);
        return why != null ? "TRD:" + id + " is not live on " + date + ": " + why + " (no P&L vector)"
                : "TRD:" + id + " is not a live trade on " + date + " (no P&L vector): unknown to the day's vectors, matured or cancelled";
    }

    // ------------------------------------------------------------------------------------------------------------------- hints

    /** Up to {@code limit} things a typed text names, each with its trade count and standalone VaR as the viewer. */
    List<Map<String, Object>> hints(Principal p, String q, int limit, java.time.LocalDate asked) {
        return hints(p, q, limit, asked, VarDate.USD);
    }

    /** The same with each standalone VaR in a reporting currency ({@code currency} on every hint). */
    List<Map<String, Object>> hints(Principal p, String q, int limit, java.time.LocalDate asked, String ccy) {
        String text = q == null ? "" : q.trim();
        if (text.isEmpty()) {
            return List.of();
        }
        VarDate d = svc.date(asked);
        int max = limit <= 0 ? HINTS : Math.min(limit, 25);
        List<Entry> found = new ArrayList<>();
        String typedWord = text.contains(":") ? text.substring(0, text.indexOf(':')).trim().toUpperCase(Locale.ROOT) : "";
        if ((ScopeExpr.WORDS.contains(typedWord) || svc.words().containsKey(typedWord)) && text.length() > typedWord.length() + 1) {
            found.add(new Entry(typedWord, text, text.substring(text.indexOf(':') + 1).trim(), "as typed", ""));
        }
        String needle = text.toLowerCase(Locale.ROOT);
        String[] words = needle.split("\\s+");
        List<Entry> all = new ArrayList<>(catalogue(d, svc.words()));
        if (p != null) {
            for (Map<String, Object> b : svc.saved().baskets(p)) {
                String id = String.valueOf(b.get("id"));
                all.add(new Entry("BASKET", "BASKET:" + id, id, String.valueOf(b.get("name")), ("basket " + id + " " + b.get("name")).toLowerCase(Locale.ROOT)));
            }
        }
        List<Entry> matches = new ArrayList<>();
        for (Entry e : all) {
            boolean ok = true;
            for (String w : words) {
                if (!e.find().contains(w)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matches.add(e);
            }
            if (matches.size() > 400) {
                break;
            }
        }
        matches.sort(Comparator.comparingInt((Entry e) -> e.id().toLowerCase(Locale.ROOT).startsWith(needle) ? 0 : 1)
                .thenComparingInt(e -> TYPES.contains(e.type()) ? TYPES.indexOf(e.type()) : TYPES.indexOf("BOOK")).thenComparing(Entry::id));
        found.addAll(matches);
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry e : found) {
            if (out.size() >= max) {
                break;
            }
            if (!seen.add(e.scope())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.type());
            m.put("id", e.id());
            m.put("name", e.name());
            m.put("scope", e.scope());
            m.put("currency", VarCurrencies.normalise(ccy));
            try {
                VarService.Scoped s = svc.scoped(p, e.scope(), asked, ccy);
                if (s.set().isEmpty() && !"as typed".equals(e.name())) {
                    continue;                                          // nothing of it the viewer sees
                }
                m.put("trades", s.set().size());
                m.put("var", VarViews.round(s.measures().var()));
                if (s.set().isEmpty()) {
                    List<String> n = notices(s);
                    m.put("notice", n.isEmpty() ? "matches nothing" : n.get(0));
                }
            } catch (DrishtiException | IllegalArgumentException x) {
                if (!"as typed".equals(e.name())) {
                    continue;                                          // not the viewer's to name one by one
                }
                m.put("trades", 0);
                m.put("var", 0);
                m.put("notice", x.getMessage());
            }
            out.add(m);
        }
        return out;
    }

    /**
     * The date's catalogue: nodes (each configured hierarchy's by its word, 2.9.5), portfolios, counterparties, positions and trades
     * (built once per date, data version and configured words).
     */
    private List<Entry> catalogue(VarDate d, Map<String, String> words) {
        String key = d.date() + "|" + d.version() + "|" + words;
        return catalogues.computeIfAbsent(key, k -> {
            catalogues.keySet().removeIf(x -> !x.equals(k) && catalogues.size() > 3);
            return build(d, words);
        });
    }

    private static List<Entry> build(VarDate d, Map<String, String> words) {
        VarBook b = d.book();
        List<Entry> out = new ArrayList<>();
        d.portfolios().values().forEach(pf -> out.add(entry("PORT", "PORT:" + pf.id(), pf.id(), pf.name() == null ? pf.id() : pf.name())));
        Map<String, String> wordOf = new HashMap<>();
        words.forEach((w, h) -> wordOf.put(h, w));
        b.nodes().values().forEach(n -> {
            String own = wordOf.get(n.hierarchy());
            if (own != null) {                                           // a configured hierarchy's node, named by its word: LL:LL-0042
                if (n.parent() != null) {
                    String id = n.id().substring(n.id().indexOf('~') + 1);
                    out.add(entry(own, own + ":" + id, id, n.name()));
                }
                return;
            }
            String type = switch (n.level() == null ? "" : n.level()) {
                case "desk" -> "DESK";
                case "book" -> "BOOK";
                case "legal-entity" -> "LE";
                case "division" -> "DIV";
                default -> null;
            };
            if (type != null && n.hierarchy().equals("LEGAL") == type.equals("LE")) {      // desks, books, divisions of MGMT; legal entities of LEGAL
                String id = n.id().substring(n.id().indexOf('~') + 1);
                out.add(entry(type, type.equals("LE") ? "LE:" + id : "NODE:" + n.id(), id, n.name()));
            }
        });
        Map<String, Integer> cptys = new HashMap<>();
        for (int o = 0; o < b.tradeCount(); o++) {
            String c = b.trades().text("COUNTERPARTY", o);
            if (c != null && !c.isBlank()) {
                cptys.merge(c, 1, Integer::sum);
            }
        }
        cptys.keySet().stream().sorted().forEach(c -> out.add(entry("CPTY", "CPTY:" + c, c, "counterparty")));
        for (int pi = 0; pi < b.positionCount(); pi++) {
            String id = b.positionId(pi);
            String ins = b.positions().text("INSTRUMENT", pi);
            out.add(entry("POS", "POS:" + id, id, ins == null ? "position" : ins));
        }
        for (int o = 0; o < b.tradeCount(); o++) {
            String id = b.tradeId(o);
            String pt = b.trades().text("PRODUCT_TYPE", o);
            String cp = b.trades().text("COUNTERPARTY", o);
            out.add(entry("TRD", "TRD:" + id, id, (pt == null ? "trade" : pt) + (cp == null ? "" : " · " + cp)));
        }
        return out;
    }

    private static Entry entry(String type, String scope, String id, String name) {
        return new Entry(type, scope, id, name, (type + " " + id + " " + name).toLowerCase(Locale.ROOT));
    }
}
