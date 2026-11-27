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
package com.ash.drishti.banking.compute.var;

import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a scope ({@link ScopeExpr}) on one business date to the set of trade ordinals it means (VAR_GUIDE.md section 5.3):
 * a node to its books' runs, positions to theirs, trades to theirs, a legal entity or a counterparty to the trades that carry it, a rule
 * over every position's columns, a portfolio or a basket through its definition. Combinations are set operations, so an overlap is
 * counted once. Ids the date does not hold are reported, never silently dropped.
 */
public final class ScopeResolver {

    /** Where saved definitions come from: a portfolio's, a person's basket, a capture's members. */
    public interface Definitions {
        /** A portfolio's definition as scope text (HIERARCHY: NODE:x; RULE: WHERE:rule; BASKET: POS:...+TRD:...), empty when unknown. */
        Optional<String> portfolio(String id);

        /** A saved basket's scope text, empty when unknown or not readable by the viewer. */
        Optional<String> basket(String name);

        /** A capture's member trades as scope text, empty when unknown or not readable by the viewer. */
        default Optional<String> capture(String id) {
            return Optional.empty();
        }
    }

    /**
     * What a scope resolved to.
     *
     * @param set the trade ordinals
     * @param canonical the scope's canonical text
     * @param node the node id when the scope is exactly one node (its history and children then apply), else null
     * @param position the position index when the scope is exactly one position, else -1
     * @param trade the trade ordinal when the scope is exactly one trade, else -1
     * @param unknown the ids it names that the date does not hold
     * @param notes what was resolved how (for the provenance box)
     * @param warnings data-quality findings about what was resolved (a legal entity's trades outside its node of the LEGAL hierarchy)
     */
    public record Resolution(OrdinalSet set, String canonical, String node, int position, int trade, List<String> unknown, List<String> notes,
            List<String> warnings) {}

    /**
     * What a scope is resolved over: a date's rows by ordinal, its positions and its hierarchies' nodes. Drishti's in-tree book is one
     * ({@link #of(VarBook)}); the market-risk engine's index is another (R2: the host resolves every scope kind to a row set, EN-68), so
     * the grammar, the baskets and the resolution stay the host's while the rows are the engine's.
     */
    public interface Book {
        /** A node's member rows; null when the date has no such node. */
        OrdinalSet members(String node);

        List<String> hierarchies();

        /** A hierarchy's root node id. */
        String rootId(String hierarchy);

        /** A position's index, its id in any case; -1 when unknown. */
        int positionIndexAnyCase(String id);

        /** A position's rows as runs, ascending. */
        void forEachPositionRun(int index, OrdinalSet.RunConsumer f);

        /** A trade's row, its id in any case; -1 when unknown. */
        int tradeOrdinalAnyCase(String id);

        /** True when the row is a position its source sends whole (false for -1). */
        boolean supplied(int ordinal);

        /** The live rows whose text column holds one of the values, ignoring case. */
        OrdinalSet tradesWhere(String column, java.util.Collection<String> values);

        int positionCount();

        /** A position's column value (a text or a number), null when absent. */
        Object positionValue(String column, int position);

        /** Drishti's in-tree book as a book to resolve over. */
        static Book of(VarBook b) {
            return new Book() {
                @Override
                public OrdinalSet members(String node) {
                    VarBook.Node n = b.node(node);
                    return n == null ? null : n.members();
                }

                @Override
                public List<String> hierarchies() {
                    return b.hierarchies();
                }

                @Override
                public String rootId(String hierarchy) {
                    return b.root(hierarchy).id();
                }

                @Override
                public int positionIndexAnyCase(String id) {
                    return b.positionIndexAnyCase(id);
                }

                @Override
                public void forEachPositionRun(int index, OrdinalSet.RunConsumer f) {
                    b.forEachPositionRun(index, f);
                }

                @Override
                public int tradeOrdinalAnyCase(String id) {
                    return b.tradeOrdinalAnyCase(id);
                }

                @Override
                public boolean supplied(int ordinal) {
                    return b.supplied(ordinal);
                }

                @Override
                public OrdinalSet tradesWhere(String column, java.util.Collection<String> values) {
                    return b.tradesWhere(column, values);
                }

                @Override
                public int positionCount() {
                    return b.positionCount();
                }

                @Override
                public Object positionValue(String column, int position) {
                    return b.positions().value(column, position);
                }
            };
        }
    }

    private static final int MAX_DEPTH = 8;
    private final Book book;
    private final Definitions defs;
    /** The configured hierarchies' scope words (2.9.5): word to hierarchy id ({@code LL -> LL}). */
    private final java.util.Map<String, String> words;

    public ScopeResolver(VarBook book, Definitions defs) {
        this(book, defs, java.util.Map.of());
    }

    /** A resolver that also knows the configured hierarchies' words ({@code words}: word to hierarchy id). */
    public ScopeResolver(VarBook book, Definitions defs, java.util.Map<String, String> words) {
        this(Book.of(book), defs, words);
    }

    /** A resolver over any {@link Book} (the engine's index on the engine path). */
    public ScopeResolver(Book book, Definitions defs, java.util.Map<String, String> words) {
        this.book = book;
        this.defs = defs;
        this.words = words == null ? java.util.Map.of() : java.util.Map.copyOf(words);
    }

    /** The configured hierarchies' words this resolver knows. */
    public java.util.Set<String> words() {
        return words.keySet();
    }

    public Resolution resolve(String text) {
        ScopeExpr e = ScopeExpr.parse(text, words.keySet());
        List<String> unknown = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        OrdinalSet set = eval(e, unknown, notes, warnings, 0);
        String node = null;
        int position = -1;
        int trade = -1;
        if (e instanceof ScopeExpr.Ref r) {
            node = switch (r.word()) {
                case "BANK", "LE", "DIV", "DESK", "BOOK", "NODE" -> nodeId(r);
                default -> words.containsKey(r.word()) ? nodeId(r) : null;
            };
            if (r.word().equals("POS") && r.ids().size() == 1) {
                position = book.positionIndexAnyCase(r.ids().get(0));
            }
            if (r.word().equals("TRD") && r.ids().size() == 1) {
                trade = book.tradeOrdinalAnyCase(r.ids().get(0));
                trade = book.supplied(trade) ? -1 : trade;
            }
        }
        return new Resolution(set, e.toString(), node, position, trade, List.copyOf(unknown), List.copyOf(notes), List.copyOf(warnings));
    }

    private OrdinalSet eval(ScopeExpr e, List<String> unknown, List<String> notes, List<String> warnings, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("the scope refers to itself through its baskets or portfolios");
        }
        return switch (e) {
            case ScopeExpr.Union u -> {
                OrdinalSet s = OrdinalSet.empty();
                for (ScopeExpr t : u.terms()) {
                    s = s.or(eval(t, unknown, notes, warnings, depth));
                }
                yield s;
            }
            case ScopeExpr.Minus m -> eval(m.left(), unknown, notes, warnings, depth).andNot(eval(m.right(), unknown, notes, warnings, depth));
            case ScopeExpr.And a -> eval(a.left(), unknown, notes, warnings, depth).and(eval(a.right(), unknown, notes, warnings, depth));
            case ScopeExpr.Ref r -> ref(r, unknown, notes, warnings, depth);
        };
    }

    private OrdinalSet ref(ScopeExpr.Ref r, List<String> unknown, List<String> notes, List<String> warnings, int depth) {
        switch (r.word()) {
            case "LE" -> {
                return legalEntity(r, unknown, notes, warnings);
            }
            case "BANK", "DIV", "DESK", "BOOK", "NODE" -> {
                String id = nodeId(r);
                if (id == null) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("node " + id);
                return book.members(id);
            }
            case "POS" -> {
                OrdinalSet.Builder out = new OrdinalSet.Builder();     // one set for all the ids, not one as wide as the date per id
                for (String id : r.ids()) {
                    int p = book.positionIndexAnyCase(id);
                    if (p < 0) {
                        unknown.add(id);
                    } else {
                        book.forEachPositionRun(p, out::addRange);       // its run, and any trade an intraday batch added to it
                    }
                }
                return out.build();
            }
            case "TRD" -> {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (String id : r.ids()) {
                    int o = book.tradeOrdinalAnyCase(id);
                    if (o >= 0 && book.supplied(o)) {                    // a supplied position: counted as the position, said in words
                        notes.add("TRD:" + id + " is a position its source system sends whole (position grain): counted as POS:" + id);
                        b.add(o);
                    } else if (o < 0) {
                        unknown.add(id);
                    } else {
                        b.add(o);
                    }
                }
                return b.build();
            }
            case "CPTY" -> {
                OrdinalSet s = book.tradesWhere("COUNTERPARTY", r.ids());
                if (s.isEmpty()) {
                    unknown.add(r.toString());
                } else {
                    notes.add("counterparty " + String.join(", ", r.ids()) + ": " + s.size() + " trades");
                }
                return s;
            }
            case "WHERE" -> {
                PositionRule rule = new PositionRule(r.arg());
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                int matched = 0;
                for (int p = 0; p < book.positionCount(); p++) {
                    int pi = p;
                    if (rule.matches(f -> book.positionValue(f, pi))) {
                        book.forEachPositionRun(p, b::addRange);
                        matched++;
                    }
                }
                notes.add("rule matched " + matched + " positions");
                return b.build();
            }
            case "PORT" -> {
                Optional<String> def = defs.portfolio(r.arg());
                if (def.isEmpty()) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("system basket " + r.arg() + " = " + def.get());
                return eval(ScopeExpr.parse(def.get(), words.keySet()), unknown, notes, warnings, depth + 1);
            }
            case "BASKET" -> {
                Optional<String> def = defs.basket(r.arg());
                if (def.isEmpty()) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("basket " + r.arg());
                return eval(ScopeExpr.parse(def.get(), words.keySet()), unknown, notes, warnings, depth + 1);
            }
            case "CAPTURE" -> {
                Optional<String> def = defs.capture(r.arg());
                if (def.isEmpty()) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("the members of capture " + r.arg());
                return eval(ScopeExpr.parse(def.get(), words.keySet()), unknown, notes, warnings, depth + 1);
            }
            default -> {
                if (words.containsKey(r.word())) {                           // a configured hierarchy's node (2.9.5)
                    String id = nodeId(r);
                    if (id == null) {
                        unknown.add(r.toString());
                        return OrdinalSet.empty();
                    }
                    notes.add("node " + id);
                    return book.members(id);
                }
                throw new IllegalArgumentException(r.word() + ": is not a scope of VaR (NSET and GROUP are the exposure engine's)");
            }
        }
    }

    /**
     * A legal entity's trades: those whose booking {@code LEGAL_ENTITY} is it ({@code LE:NY} is {@code LE-NY} too), the source of truth
     * (VAR_GUIDE.md section 5.5, decided 2026-10-25). The LEGAL hierarchy rolls up by books; when its node holds other trades, or misses some
     * of the entity's, the answer is still the trades' entity and the difference is a data-quality warning.
     */
    private OrdinalSet legalEntity(ScopeExpr.Ref r, List<String> unknown, List<String> notes, List<String> warnings) {
        String a = r.arg().toUpperCase(Locale.ROOT);
        OrdinalSet s = book.tradesWhere("LEGAL_ENTITY", List.of(a));
        String id = a;
        if (s.isEmpty() && !a.startsWith("LE-")) {
            s = book.tradesWhere("LEGAL_ENTITY", List.of("LE-" + a));
            id = "LE-" + a;
        }
        String node = nodeId(r);
        if (s.isEmpty()) {
            if (node == null) {
                unknown.add(r.toString());
                return OrdinalSet.empty();
            }
            notes.add("node " + node + " (no trade carries the legal entity)");
            return book.members(node);
        }
        notes.add("legal entity " + id + ": " + s.size() + " trades booked by it");
        if (node != null) {
            OrdinalSet members = book.members(node);
            int outside = s.andNot(members).size();
            int foreign = members.andNot(s).size();
            if (outside > 0) {
                warnings.add(outside + " trades booked by " + id + " sit outside " + node + " in the LEGAL hierarchy (their books roll up elsewhere)");
            }
            if (foreign > 0) {
                warnings.add(node + " holds " + foreign + " trades booked by another legal entity");
            }
        }
        return s;
    }

    /**
     * The node a reference names: {@code DESK:RATES}, {@code DESK:DESK-RATES} and {@code NODE:MGMT~DESK-RATES} are the same node; a
     * legal entity is in the LEGAL hierarchy, everything else in MGMT; a configured hierarchy's word names a node of it ({@code LL:LL-0042}
     * is {@code LL~LL-0042}, {@code LL:0042} too, as {@code DESK:RATES} is {@code DESK-RATES}).
     */
    public String nodeId(ScopeExpr.Ref r) {
        String a = r.arg().toUpperCase(Locale.ROOT);
        String h = words.get(r.word());
        if (h != null) {
            for (String t : List.of(h + "~" + a, h + "~" + r.word() + "-" + a)) {
                if (book.members(t) != null) {
                    return t;
                }
            }
            return null;
        }
        List<String> tries = switch (r.word()) {
            case "BANK" -> book.members("MGMT~BANK") != null || book.hierarchies().isEmpty() ? List.of("MGMT~BANK")
                    : List.of(book.rootId(book.hierarchies().get(0)));
            case "NODE" -> List.of(r.arg(), a);
            case "LE" -> List.of("LEGAL~" + a, "LEGAL~LE-" + a);
            case "DIV" -> List.of("MGMT~" + a, "MGMT~DIV-" + a);
            case "DESK" -> List.of("MGMT~" + a, "MGMT~DESK-" + a, "LEGAL~" + a, "LEGAL~DESK-" + a);
            case "BOOK" -> List.of("MGMT~" + a, "MGMT~BOOK-" + a);
            default -> List.of();
        };
        for (String t : tries) {
            if (book.members(t) != null) {
                return t;
            }
        }
        return null;
    }
}
