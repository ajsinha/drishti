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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * A scope written as text (VAR_GUIDE.md section 5.2), parsed into a tree: any set of trades.
 *
 * <pre>
 *   BANK                                   the whole bank (the primary hierarchy's root)
 *   DIV:MARKETS   DESK:RATES   BOOK:BOOK-RATES-2   NODE:MGMT~DESK-RATES      a node of a hierarchy
 *   LE:LE-NY                                                  the trades booked by a legal entity (their LEGAL_ENTITY)
 *   POS:POS-BOOK-FI-1~SEC-GOVT_BOND-USD-03,...               positions (every trade of each)
 *   TRD:MX-20000001,CLY-3000157                               trades
 *   CPTY:CP-KESTREL                                           every trade with that counterparty
 *   NSET:NS-KESTREL-NY,...   GROUP:GRP-KESTREL               netting sets, a connected group (the exposure engine's words, EXPOSURE_GUIDE.md section 4)
 *   PORT:PF-USD-RATES                       a portfolio (its definition: a node, a rule or a basket)
 *   BASKET:asha/unwind                      a basket a person saved
 *   WHERE:PRODUCT_GROUP == 'Rates' and CURRENCY == 'USD'      a rule over the positions' columns (PositionRule)
 *   A + B   A - B   A &amp; B   ( ... )            union, difference, intersection; &amp; binds before + and -
 * </pre>
 *
 * The ids of {@code POS}, {@code TRD}, {@code CPTY} and {@code NSET} are kept as written (a booking system's ids are text and may be lower case;
 * they are matched ignoring case when the date has no exact id); every other argument but a basket's or a capture's is upper case.
 *
 * An operator stands between spaces ({@code DESK:RATES - POS:X}); a hyphen inside an id ({@code MX-20000001}) is part of the id.
 * {@link #toString()} is the canonical text: words upper case, the ids of a list sorted, the terms of a union sorted, so equal scopes
 * written differently share a cache key.
 */
public sealed interface ScopeExpr {

    /** The words a reference may start with. */
    Set<String> WORDS = Set.of("BANK", "LE", "DIV", "DESK", "BOOK", "NODE", "POS", "TRD", "CPTY", "PORT", "BASKET", "WHERE", "CAPTURE", "NSET",
            "GROUP");

    /** The words whose argument is a list of ids ({@code a,b}), kept as written. */
    Set<String> LISTS = Set.of("POS", "TRD", "CPTY", "NSET");

    /** One reference: a word and its argument (empty for BANK). */
    record Ref(String word, String arg) implements ScopeExpr {
        /** The argument as a list of ids (POS, TRD and CPTY lists), sorted and unique. */
        public List<String> ids() {
            return new ArrayList<>(new TreeSet<>(Arrays.stream(arg.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()));
        }

        @Override
        public String toString() {
            if (word.equals("BANK")) {
                return "BANK";
            }
            if (LISTS.contains(word)) {
                return word + ":" + String.join(",", ids());
            }
            return word + ":" + arg;
        }
    }

    /** Positions in any of the terms (each counted once). */
    record Union(List<ScopeExpr> terms) implements ScopeExpr {
        @Override
        public String toString() {
            List<String> t = new ArrayList<>(terms.stream().map(ScopeExpr::wrapped).toList());
            java.util.Collections.sort(t);
            return String.join(" + ", t);
        }
    }

    /** Positions in {@code left} and not in {@code right}. */
    record Minus(ScopeExpr left, ScopeExpr right) implements ScopeExpr {
        @Override
        public String toString() {
            return left + " - " + right.wrapped();
        }
    }

    /** Positions in both. */
    record And(ScopeExpr left, ScopeExpr right) implements ScopeExpr {
        @Override
        public String toString() {
            return left.wrappedAnd() + " & " + right.wrappedAnd();
        }
    }

    /** The expression in parentheses when it is a combination. */
    default String wrapped() {
        return this instanceof Ref ? toString() : "(" + this + ")";
    }

    private String wrappedAnd() {
        return this instanceof Ref || this instanceof And ? toString() : "(" + this + ")";
    }

    /** Every reference in the expression, left to right. */
    default List<Ref> refs() {
        List<Ref> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(ScopeExpr e, List<Ref> out) {
        switch (e) {
            case Ref r -> out.add(r);
            case Union u -> u.terms().forEach(t -> collect(t, out));
            case Minus m -> {
                collect(m.left(), out);
                collect(m.right(), out);
            }
            case And a -> {
                collect(a.left(), out);
                collect(a.right(), out);
            }
        }
    }

    /** Parses scope text; {@link IllegalArgumentException} says what is wrong and where. */
    static ScopeExpr parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an empty scope: write BANK, DESK:<desk>, POS:<ids>, TRD:<ids>, PORT:<id> or a combination");
        }
        Parser p = new Parser(text.trim());
        ScopeExpr e = p.union();
        p.skipSpace();
        if (p.i < p.s.length()) {
            throw new IllegalArgumentException("unexpected '" + p.s.substring(p.i) + "' in the scope " + text);
        }
        return e;
    }

    /** A recursive-descent parser over the scope text. */
    final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        /** An operator (+, - or &amp;) at the cursor, standing alone between spaces (or after a closing parenthesis). */
        private char op() {
            int save = i;
            boolean spaced = i > 0 && (Character.isWhitespace(s.charAt(i - 1)) || s.charAt(i - 1) == ')');
            skipSpace();
            spaced |= i > save;
            if (i < s.length() && "+-&".indexOf(s.charAt(i)) >= 0 && spaced
                    && (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1)) || s.charAt(i + 1) == '(')) {
                return s.charAt(i);
            }
            i = save;
            return 0;
        }

        ScopeExpr union() {
            List<ScopeExpr> plus = new ArrayList<>();
            ScopeExpr cur = and();
            while (true) {
                char o = op();
                if (o == '+') {
                    i++;
                    plus.add(cur);
                    cur = and();
                } else if (o == '-') {
                    i++;
                    ScopeExpr right = and();
                    cur = plus.isEmpty() ? new Minus(cur, right) : new Minus(unionOf(plus, cur), right);
                    plus.clear();
                } else {
                    break;
                }
            }
            if (plus.isEmpty()) {
                return cur;
            }
            return unionOf(plus, cur);
        }

        private static ScopeExpr unionOf(List<ScopeExpr> plus, ScopeExpr last) {
            List<ScopeExpr> all = new ArrayList<>();
            for (ScopeExpr e : plus) {
                if (e instanceof Union u) {
                    all.addAll(u.terms());
                } else {
                    all.add(e);
                }
            }
            all.add(last);
            return new Union(List.copyOf(all));
        }

        ScopeExpr and() {
            ScopeExpr cur = atom();
            while (op() == '&') {
                i++;
                cur = new And(cur, atom());
            }
            return cur;
        }

        ScopeExpr atom() {
            skipSpace();
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                ScopeExpr e = union();
                skipSpace();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new IllegalArgumentException("a '(' without its ')' in the scope " + s);
                }
                i++;
                return e;
            }
            int start = i;
            while (i < s.length() && (Character.isLetter(s.charAt(i)))) {
                i++;
            }
            String word = s.substring(start, i).toUpperCase(Locale.ROOT);
            if (!WORDS.contains(word)) {
                throw new IllegalArgumentException("'" + (word.isEmpty() ? s.substring(start) : word) + "' is not a scope word: use one of "
                        + new TreeSet<>(WORDS));
            }
            if (word.equals("BANK") && (i >= s.length() || s.charAt(i) != ':')) {
                return new Ref("BANK", "");
            }
            if (i >= s.length() || s.charAt(i) != ':') {
                throw new IllegalArgumentException(word + " needs ':' and what it names, as " + word + ":<id>");
            }
            i++;
            return new Ref(word, word.equals("WHERE") ? rule() : arg(word));
        }

        private String arg(String word) {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) && !LISTS.contains(word) || c == '(' || c == ')') {
                    break;
                }
                if (Character.isWhitespace(c)) {            // a list of ids may be written "a, b": keep going after a comma
                    int j = i;
                    while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                        j++;
                    }
                    boolean afterComma = i > start && s.charAt(i - 1) == ',';
                    boolean beforeComma = j < s.length() && s.charAt(j) == ',';
                    if (!afterComma && !beforeComma) {
                        break;
                    }
                }
                i++;
            }
            String a = s.substring(start, i).replaceAll("\\s+", "");
            if (a.isEmpty()) {
                throw new IllegalArgumentException(word + ": names nothing");
            }
            return word.equals("BASKET") || word.equals("CAPTURE") || LISTS.contains(word) ? a : a.toUpperCase(Locale.ROOT);
        }

        /** A rule runs to the end, to an operator between spaces outside quotes and parentheses, or to an unmatched ')'. */
        private String rule() {
            int start = i;
            int depth = 0;
            boolean quoted = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '\'') {
                    quoted = !quoted;
                } else if (!quoted && c == '(') {
                    depth++;
                } else if (!quoted && c == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (!quoted && depth == 0 && Character.isWhitespace(c) && i + 2 < s.length() && "+-&".indexOf(s.charAt(i + 1)) >= 0
                        && Character.isWhitespace(s.charAt(i + 2))) {
                    break;
                }
                i++;
            }
            String r = s.substring(start, i).trim();
            new PositionRule(r);                              // refused here when it does not parse
            return r;
        }
    }
}
