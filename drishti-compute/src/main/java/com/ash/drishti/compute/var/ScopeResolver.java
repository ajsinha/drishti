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
package com.ash.drishti.compute.var;

import com.ash.drishti.compute.vector.OrdinalSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a scope ({@link ScopeExpr}) on one business date to the set of trade ordinals it means (VAR_DATA_MODEL.md section 13.1):
 * a node to its books' runs, positions to theirs, trades to theirs, a rule over every position's columns, a portfolio or a basket
 * through its definition. Combinations are set operations, so an overlap is counted once. Ids the date does not hold are reported,
 * never silently dropped.
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
     */
    public record Resolution(OrdinalSet set, String canonical, String node, int position, int trade, List<String> unknown, List<String> notes) {}

    private static final int MAX_DEPTH = 8;
    private final VarBook book;
    private final Definitions defs;

    public ScopeResolver(VarBook book, Definitions defs) {
        this.book = book;
        this.defs = defs;
    }

    public Resolution resolve(String text) {
        ScopeExpr e = ScopeExpr.parse(text);
        List<String> unknown = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        OrdinalSet set = eval(e, unknown, notes, 0);
        String node = null;
        int position = -1;
        int trade = -1;
        if (e instanceof ScopeExpr.Ref r) {
            node = switch (r.word()) {
                case "BANK", "LE", "DIV", "DESK", "BOOK", "NODE" -> nodeId(r);
                default -> null;
            };
            if (r.word().equals("POS") && r.ids().size() == 1) {
                position = book.positionIndex(r.ids().get(0));
            }
            if (r.word().equals("TRD") && r.ids().size() == 1) {
                trade = book.tradeOrdinal(r.ids().get(0));
            }
        }
        return new Resolution(set, e.toString(), node, position, trade, List.copyOf(unknown), List.copyOf(notes));
    }

    private OrdinalSet eval(ScopeExpr e, List<String> unknown, List<String> notes, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("the scope refers to itself through its baskets or portfolios");
        }
        return switch (e) {
            case ScopeExpr.Union u -> {
                OrdinalSet s = OrdinalSet.empty();
                for (ScopeExpr t : u.terms()) {
                    s = s.or(eval(t, unknown, notes, depth));
                }
                yield s;
            }
            case ScopeExpr.Minus m -> eval(m.left(), unknown, notes, depth).andNot(eval(m.right(), unknown, notes, depth));
            case ScopeExpr.And a -> eval(a.left(), unknown, notes, depth).and(eval(a.right(), unknown, notes, depth));
            case ScopeExpr.Ref r -> ref(r, unknown, notes, depth);
        };
    }

    private OrdinalSet ref(ScopeExpr.Ref r, List<String> unknown, List<String> notes, int depth) {
        switch (r.word()) {
            case "BANK", "LE", "DIV", "DESK", "BOOK", "NODE" -> {
                String id = nodeId(r);
                if (id == null) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("node " + id);
                return book.node(id).members();
            }
            case "POS" -> {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (String id : r.ids()) {
                    int p = book.positionIndex(id);
                    if (p < 0) {
                        unknown.add(id);
                    } else {
                        b.addRange(book.positionFrom(p), book.positionTo(p));
                    }
                }
                return b.build();
            }
            case "TRD" -> {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (String id : r.ids()) {
                    int o = book.tradeOrdinal(id);
                    if (o < 0) {
                        unknown.add(id);
                    } else {
                        b.add(o);
                    }
                }
                return b.build();
            }
            case "WHERE" -> {
                PositionRule rule = new PositionRule(r.arg());
                Columns pc = book.positions();
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                int matched = 0;
                for (int p = 0; p < book.positionCount(); p++) {
                    int pi = p;
                    if (rule.matches(f -> pc.value(f, pi))) {
                        b.addRange(book.positionFrom(p), book.positionTo(p));
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
                notes.add("portfolio " + r.arg() + " = " + def.get());
                return eval(ScopeExpr.parse(def.get()), unknown, notes, depth + 1);
            }
            case "BASKET" -> {
                Optional<String> def = defs.basket(r.arg());
                if (def.isEmpty()) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("basket " + r.arg());
                return eval(ScopeExpr.parse(def.get()), unknown, notes, depth + 1);
            }
            case "CAPTURE" -> {
                Optional<String> def = defs.capture(r.arg());
                if (def.isEmpty()) {
                    unknown.add(r.toString());
                    return OrdinalSet.empty();
                }
                notes.add("the members of capture " + r.arg());
                return eval(ScopeExpr.parse(def.get()), unknown, notes, depth + 1);
            }
            default -> throw new IllegalArgumentException("not a scope word: " + r.word());
        }
    }

    /**
     * The node a reference names: {@code DESK:RATES}, {@code DESK:DESK-RATES} and {@code NODE:MGMT~DESK-RATES} are the same node; a
     * legal entity is in the LEGAL hierarchy, everything else in MGMT.
     */
    public String nodeId(ScopeExpr.Ref r) {
        String a = r.arg().toUpperCase(Locale.ROOT);
        List<String> tries = switch (r.word()) {
            case "BANK" -> List.of("MGMT~BANK");
            case "NODE" -> List.of(r.arg(), a);
            case "LE" -> List.of("LEGAL~" + a, "LEGAL~LE-" + a);
            case "DIV" -> List.of("MGMT~" + a, "MGMT~DIV-" + a);
            case "DESK" -> List.of("MGMT~" + a, "MGMT~DESK-" + a, "LEGAL~" + a, "LEGAL~DESK-" + a);
            case "BOOK" -> List.of("MGMT~" + a, "MGMT~BOOK-" + a);
            default -> List.of();
        };
        for (String t : tries) {
            if (book.node(t) != null) {
                return t;
            }
        }
        return null;
    }
}
